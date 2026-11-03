// Rewrites the hand-written ModelJars coordinates the site serves to a given released version.
//
// The site carries the coordinate as a literal rather than reading Maven Central at build time, so
// that a published release can be withheld from the page -- which matters when a release turns out
// to be defective, and because generating the literal from Central would make the same commit
// render differently depending on when it was built. The cost of the literal is that somebody has
// to bump it, and bumping it by hand is how the page once served 0.1.39 while 0.1.54 was on Central,
// and how a bump in this repository was first written against 0.1.74 when the release run had
// resolved 0.1.75.
//
// This script is that bump, so the version is taken from one place and applied to every carrier.
// verify-site-version.mjs stays the check and keeps comparing two independently written things: the
// page, and what Central actually serves.
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const COORDINATE = /(org\.modeljars:modeljars:)(\d+\.\d+\.\d+)/g;
// Only the Maven block in the snippet generator; <version> elsewhere belongs to other coordinates.
const MAVEN_VERSION = /(<artifactId>modeljars<\/artifactId>\s*\n\s*<version>)(\d+\.\d+\.\d+)(<\/version>)/g;

/** Files that carry the coordinate a visitor copies, relative to the site root. */
export const CARRIERS = ["index.html", "assets/dependency-snippets.js"];

/**
 * Rewrites every coordinate carrier to `version`.
 *
 * @param {string} siteRoot directory holding the published site
 * @param {string} version released version to advertise
 * @returns {Promise<{file: string, from: string[], changed: number}[]>} per-file replacements
 */
export async function advertiseRelease(siteRoot, version) {
  if (!/^\d+\.\d+\.\d+$/.test(version)) {
    throw new Error(`Not a release version: ${version}`);
  }
  const report = [];
  for (const carrier of CARRIERS) {
    const file = path.join(siteRoot, carrier);
    const before = await readFile(file, "utf8");
    const found = new Set();
    let changed = 0;
    const rewrite = (match, prefix, current, suffix = "") => {
      found.add(current);
      if (current === version) return match;
      changed += 1;
      return `${prefix}${version}${suffix}`;
    };
    const after = before
      .replace(COORDINATE, (m, p, c) => rewrite(m, p, c))
      .replace(MAVEN_VERSION, (m, p, c, s) => rewrite(m, p, c, s));
    if (found.size === 0) {
      throw new Error(`No ModelJars coordinate found in ${carrier}; the carriers moved`);
    }
    if (changed > 0) await writeFile(file, after);
    report.push({ file: carrier, from: [...found].sort(), changed });
  }
  return report;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [siteRoot, version] = process.argv.slice(2);
  if (!siteRoot || !version) {
    console.error("usage: node tools/advertise-release.mjs <site-root> <version>");
    process.exit(2);
  }
  const report = await advertiseRelease(siteRoot, version);
  const total = report.reduce((sum, entry) => sum + entry.changed, 0);
  for (const entry of report) {
    console.log(`${entry.file}: ${entry.changed} coordinate(s) -> ${version} (was ${entry.from.join(", ")})`);
  }
  console.log(total === 0 ? `already advertising ${version}` : `rewrote ${total} coordinate(s)`);
}
