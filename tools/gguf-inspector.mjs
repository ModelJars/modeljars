const GGUF_MAGIC = Uint8Array.of(0x47, 0x47, 0x55, 0x46);
const SUPPORTED_VERSIONS = new Set([1, 2, 3]);
const DEFAULT_CHUNK_SIZE = 2_000_000;
const DEFAULT_LIMITS = Object.freeze({
  metadataEntries: 100_000,
  metadataArrayElements: 4_000_000,
  metadataBytes: 256 * 1024 * 1024,
  stringBytes: 10_000_000,
  tensors: 10_000_000,
  tensorDimensions: 8,
  arrayDepth: 4,
});

const FIXED_VALUE_BYTES = new Map([
  [0, 1],
  [1, 1],
  [2, 2],
  [3, 2],
  [4, 4],
  [5, 4],
  [6, 4],
  [7, 1],
  [10, 8],
  [11, 8],
  [12, 8],
]);

export async function inspectGguf(
  uri,
  {
    fetch: fetchImpl = globalThis.fetch,
    additionalFetchHeaders = {},
    chunkSize = DEFAULT_CHUNK_SIZE,
    limits: limitOverrides = {},
    retainMetadataArrays = [],
  } = {},
) {
  if (typeof uri !== "string" || uri.length === 0) {
    throw new TypeError("uri must be a non-empty string");
  }
  if (typeof fetchImpl !== "function") {
    throw new TypeError("fetch must be a function");
  }
  if (!Number.isSafeInteger(chunkSize) || chunkSize <= 0) {
    throw new TypeError("chunkSize must be a positive integer");
  }
  const limits = { ...DEFAULT_LIMITS, ...limitOverrides };
  validateLimits(limits);
  if (
    !Array.isArray(retainMetadataArrays) ||
    retainMetadataArrays.some((key) => typeof key !== "string" || key.length === 0)
  ) {
    throw new TypeError("retainMetadataArrays must contain non-empty metadata keys");
  }
  const retainedArrayKeys = new Set(retainMetadataArrays);

  const reader = new RangeReader(uri, fetchImpl, additionalFetchHeaders, chunkSize);
  const magic = await reader.readBytes(4);
  if (!magic.every((value, index) => value === GGUF_MAGIC[index])) {
    throw new Error("not a valid GGUF file: missing GGUF magic number");
  }

  const versionBytes = await reader.readBytes(4);
  const versionView = dataView(versionBytes);
  const littleEndianVersion = versionView.getUint32(0, true);
  const bigEndianVersion = versionView.getUint32(0, false);
  const littleEndian = SUPPORTED_VERSIONS.has(littleEndianVersion);
  const version = littleEndian ? littleEndianVersion : bigEndianVersion;
  if (!SUPPORTED_VERSIONS.has(version)) {
    throw new Error(`not a valid GGUF file: unsupported version "${version}"`);
  }

  const tensorCount = await readVersionedSize(reader, version, littleEndian);
  const metadataCount = await readVersionedSize(reader, version, littleEndian);
  assertLimit(tensorCount, limits.tensors, "Tensor count");
  assertLimit(metadataCount, limits.metadataEntries, "KV metadata count");

  const metadataStart = reader.position;
  const metadata = {
    version,
    tensor_count: tensorCount,
    kv_count: metadataCount,
  };
  for (let index = 0; index < metadataCount; index += 1) {
    const key = await readString(reader, version, littleEndian, limits.stringBytes);
    const type = await reader.readUint32(littleEndian);
    assertValueType(type);
    if (type === 9) {
      if (retainedArrayKeys.has(key)) {
        metadata[key] = await readMetadataArray(reader, version, littleEndian, limits, 0);
      } else {
        await skipMetadataValue(reader, type, version, littleEndian, limits, 0);
      }
    } else {
      metadata[key] = await readScalarValue(
        reader,
        type,
        version,
        littleEndian,
        limits.stringBytes,
      );
    }
    assertLimit(reader.position - metadataStart, limits.metadataBytes, "Metadata byte count");
  }

  const tensorInfos = [];
  let parameterCount = 0;
  for (let index = 0; index < tensorCount; index += 1) {
    const name = await readString(reader, version, littleEndian, limits.stringBytes);
    const dimensions = await reader.readUint32(littleEndian);
    assertLimit(dimensions, limits.tensorDimensions, "Tensor dimension count");
    const shape = [];
    let tensorParameters = 1;
    for (let dimension = 0; dimension < dimensions; dimension += 1) {
      const size = await readVersionedSize(reader, version, littleEndian);
      shape.push(size);
      tensorParameters = safeProduct(tensorParameters, size, `Tensor ${name} parameter count`);
    }
    const dtype = await reader.readUint32(littleEndian);
    const offset = await reader.readBigUint64(littleEndian);
    parameterCount = safeSum(parameterCount, tensorParameters, "GGUF parameter count");
    tensorInfos.push({ name, n_dims: dimensions, shape, dtype, offset });
  }

  if (parameterCount <= 0) {
    throw new Error("GGUF parameter count must be positive");
  }
  return { metadata, tensorInfos, parameterCount, littleEndian };
}

class RangeReader {
  constructor(uri, fetchImpl, additionalHeaders, chunkSize) {
    this.uri = uri;
    this.fetchImpl = fetchImpl;
    this.additionalHeaders = additionalHeaders;
    this.chunkSize = chunkSize;
  }

  position = 0;
  totalBytes;
  window = new Uint8Array(0);
  windowStart = 0;

  async readBytes(length) {
    this.#validateLength(length);
    await this.#ensure(length);
    const start = this.position - this.windowStart;
    const value = this.window.subarray(start, start + length);
    this.position += length;
    return value;
  }

  skip(length) {
    this.#validateLength(length);
    const next = safeSum(this.position, length, "GGUF byte offset");
    if (this.totalBytes !== undefined && next > this.totalBytes) {
      throw new RangeError(`GGUF value extends past the ${this.totalBytes}-byte artifact`);
    }
    this.position = next;
  }

  async readUint8() {
    return (await this.readBytes(1))[0];
  }

  async readInt8() {
    return dataView(await this.readBytes(1)).getInt8(0);
  }

  async readUint16(littleEndian) {
    return dataView(await this.readBytes(2)).getUint16(0, littleEndian);
  }

  async readInt16(littleEndian) {
    return dataView(await this.readBytes(2)).getInt16(0, littleEndian);
  }

  async readUint32(littleEndian) {
    return dataView(await this.readBytes(4)).getUint32(0, littleEndian);
  }

  async readInt32(littleEndian) {
    return dataView(await this.readBytes(4)).getInt32(0, littleEndian);
  }

  async readFloat32(littleEndian) {
    return dataView(await this.readBytes(4)).getFloat32(0, littleEndian);
  }

  async readBigUint64(littleEndian) {
    return dataView(await this.readBytes(8)).getBigUint64(0, littleEndian);
  }

  async readBigInt64(littleEndian) {
    return dataView(await this.readBytes(8)).getBigInt64(0, littleEndian);
  }

  async readFloat64(littleEndian) {
    return dataView(await this.readBytes(8)).getFloat64(0, littleEndian);
  }

  async skipStrings(count, version, littleEndian, maximumStringBytes) {
    const lengthBytes = version === 1 ? 4 : 8;
    for (let index = 0; index < count; index += 1) {
      if (!this.#available(lengthBytes)) {
        await this.#ensure(lengthBytes);
      }
      const relativeStart = this.position - this.windowStart;
      const view = new DataView(
        this.window.buffer,
        this.window.byteOffset + relativeStart,
        lengthBytes,
      );
      const rawLength =
        version === 1
          ? BigInt(view.getUint32(0, littleEndian))
          : view.getBigUint64(0, littleEndian);
      if (rawLength > BigInt(Number.MAX_SAFE_INTEGER)) {
        throw new RangeError(`GGUF size ${rawLength} exceeds JavaScript's safe integer range`);
      }
      const length = Number(rawLength);
      assertLimit(length, maximumStringBytes, "String byte length");
      this.position += lengthBytes;
      this.skip(length);
    }
  }

  async #ensure(length) {
    if (this.#available(length)) {
      return;
    }

    const requestedEnd = safeSum(this.position, Math.max(this.chunkSize, length), "Range end") - 1;
    const response = await this.fetchImpl(this.uri, {
      headers: {
        ...this.additionalHeaders,
        Range: `bytes=${this.position}-${requestedEnd}`,
      },
    });
    if (response.status !== 206) {
      await response.body?.cancel().catch(() => {});
      throw new Error(
        `GGUF inspection requires HTTP byte ranges; server returned ${response.status}`,
      );
    }
    const contentRange = parseContentRange(response.headers.get("content-range"));
    if (contentRange.start !== this.position) {
      await response.body?.cancel().catch(() => {});
      throw new Error(
        `Server returned range starting at ${contentRange.start}; expected ${this.position}`,
      );
    }
    if (this.totalBytes !== undefined && this.totalBytes !== contentRange.total) {
      await response.body?.cancel().catch(() => {});
      throw new Error("GGUF artifact size changed during metadata inspection");
    }
    this.totalBytes = contentRange.total;
    const bytes = new Uint8Array(await response.arrayBuffer());
    const declaredLength = contentRange.end - contentRange.start + 1;
    if (bytes.length !== declaredLength) {
      throw new Error(
        `Server returned ${bytes.length} bytes for a declared ${declaredLength}-byte range`,
      );
    }
    if (bytes.length < length) {
      throw new RangeError(`Unexpected end of GGUF artifact at byte ${this.position}`);
    }
    this.window = bytes;
    this.windowStart = contentRange.start;
  }

  #available(length) {
    const relativeStart = this.position - this.windowStart;
    return relativeStart >= 0 && relativeStart + length <= this.window.length;
  }

  #validateLength(length) {
    if (!Number.isSafeInteger(length) || length < 0) {
      throw new RangeError(`Invalid GGUF byte length: ${length}`);
    }
  }
}

async function readVersionedSize(reader, version, littleEndian) {
  const raw =
    version === 1
      ? BigInt(await reader.readUint32(littleEndian))
      : await reader.readBigUint64(littleEndian);
  if (raw > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new RangeError(`GGUF size ${raw} exceeds JavaScript's safe integer range`);
  }
  return Number(raw);
}

async function readString(reader, version, littleEndian, maximumBytes) {
  const length = await readVersionedSize(reader, version, littleEndian);
  assertLimit(length, maximumBytes, "String byte length");
  return new TextDecoder().decode(await reader.readBytes(length));
}

async function readScalarValue(reader, type, version, littleEndian, maximumStringBytes) {
  switch (type) {
    case 0:
      return reader.readUint8();
    case 1:
      return reader.readInt8();
    case 2:
      return reader.readUint16(littleEndian);
    case 3:
      return reader.readInt16(littleEndian);
    case 4:
      return reader.readUint32(littleEndian);
    case 5:
      return reader.readInt32(littleEndian);
    case 6:
      return reader.readFloat32(littleEndian);
    case 7:
      return (await reader.readUint8()) !== 0;
    case 8:
      return readString(reader, version, littleEndian, maximumStringBytes);
    case 10:
      return safeBigIntValue(await reader.readBigUint64(littleEndian));
    case 11:
      return safeBigIntValue(await reader.readBigInt64(littleEndian));
    case 12:
      return reader.readFloat64(littleEndian);
    default:
      throw new Error(`Unsupported scalar GGUF metadata type: ${type}`);
  }
}

async function skipMetadataValue(reader, type, version, littleEndian, limits, depth) {
  const fixedBytes = FIXED_VALUE_BYTES.get(type);
  if (fixedBytes !== undefined) {
    reader.skip(fixedBytes);
    return;
  }
  if (type === 8) {
    const length = await readVersionedSize(reader, version, littleEndian);
    assertLimit(length, limits.stringBytes, "String byte length");
    reader.skip(length);
    return;
  }
  if (type !== 9) {
    throw new Error(`Unsupported GGUF metadata type: ${type}`);
  }
  if (depth >= limits.arrayDepth) {
    throw new Error(`Nested GGUF array depth exceeds ${limits.arrayDepth}`);
  }

  const elementType = await reader.readUint32(littleEndian);
  assertValueType(elementType);
  const elementCount = await readVersionedSize(reader, version, littleEndian);
  assertLimit(elementCount, limits.metadataArrayElements, "Metadata array length");
  const elementBytes = FIXED_VALUE_BYTES.get(elementType);
  if (elementBytes !== undefined) {
    reader.skip(safeProduct(elementCount, elementBytes, "Metadata array byte length"));
    return;
  }
  if (elementType === 8) {
    await reader.skipStrings(elementCount, version, littleEndian, limits.stringBytes);
    return;
  }
  for (let index = 0; index < elementCount; index += 1) {
    await skipMetadataValue(reader, elementType, version, littleEndian, limits, depth + 1);
  }
}

async function readMetadataArray(reader, version, littleEndian, limits, depth) {
  if (depth >= limits.arrayDepth) {
    throw new Error(`Nested GGUF array depth exceeds ${limits.arrayDepth}`);
  }
  const elementType = await reader.readUint32(littleEndian);
  assertValueType(elementType);
  const elementCount = await readVersionedSize(reader, version, littleEndian);
  assertLimit(elementCount, limits.metadataArrayElements, "Metadata array length");

  if (elementType === 0) {
    return (await reader.readBytes(elementCount)).slice();
  }
  if (elementType === 9) {
    const values = [];
    for (let index = 0; index < elementCount; index += 1) {
      values.push(await readMetadataArray(reader, version, littleEndian, limits, depth + 1));
    }
    return values;
  }
  const values = [];
  for (let index = 0; index < elementCount; index += 1) {
    values.push(
      await readScalarValue(reader, elementType, version, littleEndian, limits.stringBytes),
    );
  }
  return values;
}

function assertValueType(type) {
  if (!Number.isInteger(type) || type < 0 || type > 12) {
    throw new Error(`Unsupported GGUF metadata type: ${type}`);
  }
}

function assertLimit(value, maximum, label) {
  if (!Number.isSafeInteger(value) || value < 0 || value > maximum) {
    throw new RangeError(`${label} ${value} exceeds maximum allowed (${maximum})`);
  }
}

function validateLimits(limits) {
  for (const [name, value] of Object.entries(limits)) {
    if (!Number.isSafeInteger(value) || value <= 0) {
      throw new TypeError(`limits.${name} must be a positive integer`);
    }
  }
}

function safeBigIntValue(value) {
  if (value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER)) {
    return Number(value);
  }
  return value;
}

function safeProduct(left, right, label) {
  const value = left * right;
  if (!Number.isSafeInteger(value)) {
    throw new RangeError(`${label} exceeds JavaScript's safe integer range`);
  }
  return value;
}

function safeSum(left, right, label) {
  const value = left + right;
  if (!Number.isSafeInteger(value)) {
    throw new RangeError(`${label} exceeds JavaScript's safe integer range`);
  }
  return value;
}

function parseContentRange(value) {
  const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value || "");
  if (!match) {
    throw new Error(`Missing or invalid Content-Range header: ${value}`);
  }
  const [, rawStart, rawEnd, rawTotal] = match;
  const start = Number.parseInt(rawStart, 10);
  const end = Number.parseInt(rawEnd, 10);
  const total = Number.parseInt(rawTotal, 10);
  if (![start, end, total].every(Number.isSafeInteger) || start > end || end >= total) {
    throw new Error(`Invalid Content-Range header: ${value}`);
  }
  return { start, end, total };
}

function dataView(bytes) {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}
