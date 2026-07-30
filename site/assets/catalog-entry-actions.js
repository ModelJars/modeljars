import { dependencySnippet } from "./dependency-snippets.js";

function escapeAttribute(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function renderDependencyCopyActions(model) {
  const coordinate = String(model.markerCoordinate || "");
  dependencySnippet("maven", coordinate);
  const name = escapeAttribute(model.name);
  const escapedCoordinate = escapeAttribute(coordinate);

  return `<div class="entry-copy-actions" aria-label="Copy dependency">
    <button
      class="dependency-copy-button"
      type="button"
      data-build-tool="maven"
      data-coordinate="${escapedCoordinate}"
      aria-label="Copy Maven dependency for ${name}"
      title="Copy Maven dependency">
      <img
        class="dependency-tool-icon dependency-tool-icon-maven"
        src="/assets/apachemaven.svg"
        alt=""
        width="20"
        height="20">
    </button>
    <button
      class="dependency-copy-button"
      type="button"
      data-build-tool="gradle"
      data-coordinate="${escapedCoordinate}"
      aria-label="Copy Gradle dependency for ${name}"
      title="Copy Gradle dependency">
      <img
        class="dependency-tool-icon dependency-tool-icon-gradle"
        src="/assets/gradle.svg"
        alt=""
        width="18"
        height="18">
    </button>
  </div>`;
}
