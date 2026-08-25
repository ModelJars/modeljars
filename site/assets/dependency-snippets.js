function coordinateParts(coordinate) {
  const parts = String(coordinate || "").split(":");
  if (parts.length !== 3 || parts.some((part) => !part)) {
    throw new Error(`Invalid Maven coordinate: ${coordinate}`);
  }
  return parts;
}

export function gradleDependencySnippet(coordinate) {
  coordinateParts(coordinate);
  return `implementation("${coordinate}")`;
}

export function mavenDependencySnippet(coordinate) {
  const [groupId, artifactId, version] = coordinateParts(coordinate);
  return `<dependency>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>${version}</version>
</dependency>`;
}

export function gradleSnippet(coordinate) {
  return `implementation("org.modeljars:modeljars:0.1.18")
${gradleDependencySnippet(coordinate)}`;
}

export function mavenSnippet(coordinate) {
  return `<dependency>
  <groupId>org.modeljars</groupId>
  <artifactId>modeljars</artifactId>
  <version>0.1.18</version>
</dependency>
${mavenDependencySnippet(coordinate)}`;
}

export function dependencySnippet(buildTool, coordinate) {
  if (buildTool === "gradle") return gradleDependencySnippet(coordinate);
  if (buildTool === "maven") return mavenDependencySnippet(coordinate);
  throw new Error(`Unsupported build tool: ${buildTool}`);
}

export async function copyDependencySnippet(
  buildTool,
  coordinate,
  clipboard = globalThis.navigator?.clipboard,
) {
  if (!clipboard || typeof clipboard.writeText !== "function") {
    throw new Error("Clipboard API is unavailable");
  }
  const snippet = dependencySnippet(buildTool, coordinate);
  await clipboard.writeText(snippet);
  return snippet;
}
