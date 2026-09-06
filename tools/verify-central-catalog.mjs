import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const DEFAULT_REPOSITORY = "https://repo1.maven.org/maven2";

function markerFiles(coordinate, repository) {
  const parts = coordinate.split(":");
  if (parts.length !== 3 || parts.some((part) => !part)) {
    throw new Error(`Invalid Maven marker coordinate: ${coordinate}`);
  }
  const [group, artifact, version] = parts;
  const root = `${repository.replace(/\/$/, "")}/${group.replaceAll(".", "/")}/${artifact}/${version}`;
  return [
    ["POM", `${root}/${artifact}-${version}.pom`],
    ["JAR", `${root}/${artifact}-${version}.jar`],
  ];
}

const pause = (milliseconds) =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

async function probe(url, attempts, retryDelayMs) {
  let result;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, { method: "HEAD", redirect: "follow" });
      result = { status: response.status };
      if (response.status === 200) return result;
    } catch (error) {
      result = { error };
    }
    if (attempt < attempts) await pause(retryDelayMs);
  }
  return result;
}

export async function verifyCentralCatalog(
  catalogPath,
  {
    repository = DEFAULT_REPOSITORY,
    attempts = 1,
    retryDelayMs = 0,
  } = {},
) {
  const catalog = JSON.parse(await readFile(catalogPath, "utf8"));
  if (!Array.isArray(catalog)) {
    throw new Error(`${catalogPath} must contain the generated public catalog array`);
  }

  const failures = [];
  for (const model of catalog) {
    if (!model?.id || !model?.markerCoordinate) {
      failures.push(`catalog entry lacks an id or markerCoordinate: ${JSON.stringify(model)}`);
      continue;
    }
    for (const [kind, url] of markerFiles(model.markerCoordinate, repository)) {
      const result = await probe(url, attempts, retryDelayMs);
      if (result.status !== 200) {
        failures.push(
          `${model.id} ${kind} ${result.error ? "unreachable" : `returned ${result.status}`} (${url})`,
        );
      }
    }
  }

  if (failures.length) {
    throw new Error(
      `Public catalog contains marker artifacts unavailable from Maven Central:\n${failures.join("\n")}`,
    );
  }
  return catalog.length;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const catalogPath = process.argv[2];
  if (!catalogPath) {
    console.error("Usage: node tools/verify-central-catalog.mjs <generated-catalog.json>");
    process.exitCode = 2;
  } else {
    const attempts = Number.parseInt(process.env.MODELJARS_CENTRAL_ATTEMPTS ?? "1", 10);
    const retryDelayMs = Number.parseInt(
      process.env.MODELJARS_CENTRAL_RETRY_DELAY_MS ?? "0",
      10,
    );
    try {
      const count = await verifyCentralCatalog(catalogPath, { attempts, retryDelayMs });
      console.log(`Verified ${count} public model markers on Maven Central.`);
    } catch (error) {
      console.error(error.message);
      process.exitCode = 1;
    }
  }
}
