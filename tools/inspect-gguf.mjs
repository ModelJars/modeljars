import { readFile } from "node:fs/promises";

import { gguf } from "@huggingface/gguf";

const id = process.argv[2];
if (!id) {
  throw new Error("Usage: npm run catalog:inspect -- <catalog-id>");
}

const catalog = JSON.parse(
  await readFile(new URL("../catalog/models.json", import.meta.url), "utf8"),
);
const model = catalog.models.find((candidate) => candidate.id === id);
if (!model) {
  throw new Error(`Unknown catalog ID: ${id}`);
}

const { metadata, tensorInfos } = await gguf(model.downloadUri);
const selected = Object.fromEntries(
  Object.entries(metadata).filter(
    ([key, value]) =>
      !key.startsWith("tokenizer.") &&
      !Array.isArray(value) &&
      (key.includes("attention") ||
        key.includes("block") ||
        key.includes("layer") ||
        key.includes("ssm") ||
        key.includes("state") ||
        key.includes("conv") ||
        key === "general.architecture"),
  ),
);
process.stdout.write(
  `${JSON.stringify(
    {
      metadata: selected,
      relevantTensorNames: tensorInfos
        .map((tensor) => tensor.name)
        .filter((name) => /(?:attn|attention|ssm|shortconv)/.test(name))
        .slice(0, 160),
    },
    null,
    2,
  )}\n`,
);
