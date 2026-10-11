import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const root = new URL("../", import.meta.url);
const properties = readFileSync(new URL("gradle.properties", root), "utf8");
const version = /^modeljarsVersion=(.+)$/m.exec(properties)?.[1];
const { compositions } = JSON.parse(
  readFileSync(new URL("catalog/compositions.json", root), "utf8"),
);

test("catalog composition dependencies follow the library release", () => {
  assert.ok(version, "gradle.properties must declare modeljarsVersion");
  assert.ok(compositions.length > 0);
  for (const composition of compositions) {
    const [group, artifact, coordinateVersion] = composition.markerCoordinate.split(":");
    assert.equal(group, "org.modeljars.composite");
    assert.equal(
      coordinateVersion,
      version,
      `${composition.id}: CLI and site must select the composition built in this release`,
    );
    assert.equal(composition.modelVersion, version);
    const expectedPath = `/maven2/${group.replaceAll(".", "/")}/${artifact}/${version}/${artifact}-${version}.jar`;
    assert.equal(new URL(composition.downloadUri).pathname, expectedPath);
  }
});
