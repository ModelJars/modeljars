import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

function requireVersion(version) {
  if (!/^[A-Za-z0-9._-]+$/.test(version || "")) {
    throw new Error("site asset version must contain only letters, numbers, dots, underscores, or hyphens");
  }
  return version;
}

export function versionHtml(source, version) {
  const token = requireVersion(version);
  return source.replace(
    /((?:src|href)="\/assets\/[^"?]+\.(?:js|css))(?:\?v=[^"]*)?(")/g,
    `$1?v=${token}$2`,
  );
}

export function versionJavaScript(source, version) {
  const token = requireVersion(version);
  return source
    .replace(
      /(\bfrom\s+["'])(\.\/[^"'?]+\.js)(?:\?v=[^"']*)?(["'])/g,
      `$1$2?v=${token}$3`,
    )
    .replace(
      /(fetch\(\s*["'])(\/[^"'?]+\.json)(?:\?v=[^"']*)?(["'])/g,
      `$1$2?v=${token}$3`,
    );
}

async function filesUnder(root) {
  const entries = await readdir(root, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const target = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...(await filesUnder(target)));
    else if (entry.isFile()) files.push(target);
  }
  return files;
}

export async function versionGeneratedSite(siteRoot, version) {
  const root = path.resolve(siteRoot);
  const files = await filesUnder(root);
  let changed = 0;
  for (const file of files) {
    const transform = file.endsWith(".html")
      ? versionHtml
      : file.endsWith(".js")
        ? versionJavaScript
        : null;
    if (!transform) continue;
    const source = await readFile(file, "utf8");
    const updated = transform(source, version);
    if (updated === source) continue;
    await writeFile(file, updated, "utf8");
    changed += 1;
  }
  return changed;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const siteRoot = process.argv[2];
  const version = process.argv[3] || process.env.GITHUB_SHA;
  if (!siteRoot || !version) {
    throw new Error("usage: node tools/site-asset-versioning.mjs <site-root> <version>");
  }
  const changed = await versionGeneratedSite(siteRoot, version);
  console.log(`Versioned browser references in ${changed} generated site files with ${version}`);
}

