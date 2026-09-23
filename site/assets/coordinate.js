/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Every marker group is `org.modeljars.<source>`, so the prefix carries one bit of information --
 * where the artifact came from -- and then repeats on every row. A badge says it once; the
 * coordinate then only has to carry what distinguishes this model from the next.
 *
 * The full coordinate is never discarded. What the copy buttons emit is unchanged, because a
 * shortened coordinate pasted into a build file does not resolve.
 */
const SOURCE_LABELS = {
  "org.modeljars.huggingface": "HuggingFace",
  "org.modeljars.github": "GitHub",
  "org.modeljars.composite": "ModelJars",
};

/**
 * Splits a marker coordinate into the source it came from and the part worth reading.
 *
 * @param {string} coordinate full `group:artifact:version` marker coordinate
 * @returns {{source: string|null, label: string|null, short: string, full: string}}
 */
export function describeCoordinate(coordinate) {
  const full = String(coordinate ?? "");
  const parts = full.split(":");
  if (parts.length !== 3) {
    // Not a coordinate we recognise. Show it whole rather than guess at its shape.
    return { source: null, label: null, short: full, full };
  }
  const [group, artifact, version] = parts;
  const label = SOURCE_LABELS[group] ?? null;
  return {
    source: label ? group : null,
    label,
    short: label ? `${artifact}:${version}` : full,
    full,
  };
}
