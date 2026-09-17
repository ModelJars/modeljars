import assert from "node:assert/strict";
import test from "node:test";

import { formatBytes, formatParameters } from "./resource-profile.js";

test("formats binary sizes for catalog cards", () => {
  assert.equal(formatBytes(1_536_870_912), "1.43 GiB");
});

test("formats parameter counts", () => {
  assert.equal(formatParameters(3_075_098_624), "3.08B");
  assert.equal(formatParameters(596_049_920), "596M");
});
