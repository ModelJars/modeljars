import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

/**
 * A COMPOSITION'S PIN MUST NAME A COORDINATE THIS CATALOG PUBLISHES.
 *
 * A composition pins its base twice: as a `ModelJar.of("group:artifact:version")` constant in its
 * Java source, and as the `runtimeOnly` dependency its Gradle module declares. Nothing compared
 * either of them to `catalog/models.json`, and no composition module has tests, so the coordinate is
 * resolved by nothing in the build.
 *
 * MEASURED 2026-09-23: with all three moved to an unpublished `3.5.0-q4_k_m.2`, `spotlessCheck test
 * verifyCatalog verifyMarkerPublicationIndependence` stayed green. A pin can point at a version that
 * does not exist, or drift from the catalog entry whose evidence it stands on, and the build says
 * nothing — the publication gate is the first thing that notices, which is the failure this repo just
 * had one level down.
 */
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const CATALOG = JSON.parse(readFileSync(join(ROOT, "catalog/models.json"), "utf8"));
const GRADLE = readFileSync(join(ROOT, "build.gradle.kts"), "utf8");
// A pin is often SPLIT ACROSS LINES as concatenated literals, so take everything up to the
// closing paren and join the string parts — reading only the first literal silently truncated
// the granite specialist coordinate to "org.modeljars.github:modeljars.activated-adapters.".
const CALL = /ModelJar\.of\(([\s\S]*?)\)\s*;/g;
const LITERAL = /"([^"]*)"/g;

const publishedCoordinates = new Set(
  CATALOG.models.map((model) => model.markerCoordinate),
);

function compositionModules() {
  return readdirSync(ROOT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("modeljars-composite-"))
    .map((entry) => entry.name);
}

function pinsIn(module) {
  const sources = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith(".java")) sources.push(path);
    }
  };
  walk(join(ROOT, module, "src/main/java"));
  const pins = [];
  for (const source of sources) {
    const text = readFileSync(source, "utf8");
    for (const call of text.matchAll(CALL)) {
      const coordinate = [...call[1].matchAll(LITERAL)].map((part) => part[1]).join("");
      if (coordinate) pins.push({ source, coordinate });
    }
  }
  return pins;
}

test("there are composition modules to check", () => {
  assert.ok(compositionModules().length > 0, "no modeljars-composite-* module found");
});

test("the catalog parses into coordinates", () => {
  assert.ok(publishedCoordinates.size > 0, "no markerCoordinate parsed — every check would be vacuous");
});

test("every pinned coordinate is one the catalog publishes", () => {
  for (const module of compositionModules()) {
    for (const { source, coordinate } of pinsIn(module)) {
      assert.ok(
        publishedCoordinates.has(coordinate),
        `${source} pins ${coordinate}, which no catalog/models.json entry publishes. A pin that ` +
          `names no catalog entry resolves against nothing and cannot carry that entry's evidence.`,
      );
    }
  }
});

test("a module's runtimeOnly dependency names the coordinate its source pins", () => {
  for (const module of compositionModules()) {
    const pins = pinsIn(module);
    if (pins.length === 0) continue;
    // the Gradle side splits long coordinates across lines with `+` exactly as the Java side does,
    // so it is joined the same way rather than read one literal at a time
    const declared = [...GRADLE.matchAll(/runtimeOnly\(([\s\S]*?)\)/g)].map((call) =>
      [...call[1].matchAll(LITERAL)].map((part) => part[1]).join(""),
    );
    for (const { source, coordinate } of pins) {
      assert.ok(
        declared.includes(coordinate),
        `${source} pins ${coordinate}, but no runtimeOnly declaration in build.gradle.kts names it. ` +
          `The constant and the dependency have drifted, so the jar the code names is not the jar ` +
          `the module resolves.`,
      );
    }
  }
});
