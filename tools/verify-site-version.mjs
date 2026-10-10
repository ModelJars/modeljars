// Fails the site build when the published dependency snippet names a version that is not the
// newest release.
//
// modeljars.org served `org.modeljars:modeljars:0.1.39` while 0.1.54 was on Maven Central: fifteen
// releases of drift in the one line on the page that visitors copy into a build file. Nothing caught
// it because nothing compared the two -- the version is written by hand in site/index.html and the
// release version comes from the newest v* tag, and no step read both.
//
// Maven Central is the source of truth for a usable released version. A source tag alone does not
// prove that visitors can resolve the dependency shown on the page.
import { readFile } from "node:fs/promises";
import path from "node:path";

const SNIPPET = /org\.modeljars:modeljars:(\d+\.\d+\.\d+)/g;

// Both sources: the static page and the script that generates the copy-paste snippets. Fixing only
// index.html left assets/dependency-snippets.js still emitting the old version, which is what the
// site actually serves into the snippet boxes.
const SOURCES = ["index.html", "assets/dependency-snippets.js"];

export async function verifySiteVersion(siteRoot, released) {
  let checked = 0;
  const stale = new Map();
  for (const relative of SOURCES) {
    const target = path.join(siteRoot, relative);
    let text;
    try {
      text = await readFile(target, "utf8");
    } catch {
      continue; // a source the built site does not contain is not a failure
    }
    const found = [...text.matchAll(SNIPPET)].map((match) => match[1]);
    checked += found.length;
    for (const version of found) {
      if (version !== released) {
        stale.set(`${relative}:${version}`, version);
      }
    }
  }
  if (checked === 0) {
    throw new Error(
      `none of ${SOURCES.join(", ")} under ${siteRoot} declares an org.modeljars:modeljars coordinate`,
    );
  }
  if (stale.size > 0) {
    throw new Error(
      `the site advertises org.modeljars:modeljars at ${[...stale.keys()].join(", ")} ` +
        `but the newest release is ${released}. Update them, or the page hands visitors a ` +
        `dependency that is not the current one.`,
    );
  }
  return { checked, released };
}

// Maven Central is the source of truth for "the newest release", and it needs no git state. The first
// version of this read `git describe --tags`, which fails under actions/checkout: the deploy clone is
// shallow and carries no tags, so the gate meant to keep the site current is what stopped the site
// deploying at all. Central is also what the neighbouring verify-central-catalog step already queries.
const METADATA_URL =
  "https://repo1.maven.org/maven2/org/modeljars/modeljars/maven-metadata.xml";

async function newestReleasedVersion() {
  const response = await fetch(METADATA_URL);
  if (!response.ok) {
    throw new Error(`could not read ${METADATA_URL}: HTTP ${response.status}`);
  }
  const xml = await response.text();
  const match = /<release>(\d+\.\d+\.\d+)<\/release>/.exec(xml);
  if (!match) {
    throw new Error(`${METADATA_URL} declares no <release> version`);
  }
  return match[1];
}

const invokedDirectly = process.argv[1] && process.argv[1].endsWith("verify-site-version.mjs");
if (invokedDirectly) {
  const siteRoot = process.argv[2];
  if (!siteRoot) {
    console.error("usage: node tools/verify-site-version.mjs <site-root> [released-version]");
    process.exit(2);
  }
  const released = process.argv[3] || (await newestReleasedVersion());
  const result = await verifySiteVersion(siteRoot, released);
  console.log(`site version gate: ${result.checked} coordinate(s) match ${result.released}`);
}
