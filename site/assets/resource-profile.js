export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "Unknown";
  }
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${unit === 0 ? value : value.toFixed(2)} ${units[unit]}`;
}

export function formatParameters(parameters) {
  if (!Number.isFinite(parameters) || parameters <= 0) {
    return "Unknown";
  }
  const units = [
    [1_000_000_000_000, "T"],
    [1_000_000_000, "B"],
    [1_000_000, "M"],
    [1_000, "K"],
  ];
  for (const [divisor, suffix] of units) {
    if (parameters >= divisor) {
      const value = parameters / divisor;
      const digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
      return `${Number(value.toFixed(digits))}${suffix}`;
    }
  }
  return parameters.toLocaleString("en-US");
}
