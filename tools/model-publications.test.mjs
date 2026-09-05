import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  catalogPublicationDelta,
  filterQualifiedPublications,
  githubPublicationOutputs,
  publicationTaskName,
  selectCatalogPublications,
} from "./model-publications.mjs";

function model(overrides = {}) {
  return {
    id: "qwen3_0_6b_q4_0",
    name: "Qwen3 0.6B GGUF Q4_0",
    markerCoordinate:
      "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1",
    revision: "a".repeat(40),
    sha256: "b".repeat(64),
    sizeBytes: 428970080,
    capabilities: ["text-generation", "chat"],
    ...overrides,
  };
}

function catalog(models) {
  return { schemaVersion: 2, models };
}

function profiles(entries) {
  return { schemaVersion: 1, profiles: entries };
}

function qualifications(entries) {
  return { schemaVersion: 1, entries };
}

const toolsDirectory = path.dirname(fileURLToPath(import.meta.url));

test("selects one independent publication for a newly accepted model", () => {
  const entry = model();

  assert.deepEqual(catalogPublicationDelta(catalog([]), catalog([entry])), {
    publications: [
      {
        change: "added",
        coordinate: entry.markerCoordinate,
        githubTaskName:
          ":modeljars-catalog:publishMarkerQwen306bQ40PublicationToGitHubPackagesRepository",
        id: entry.id,
        jarTaskName: ":modeljars-catalog:markerJarQwen306bQ40",
        publicationName: "markerQwen306bQ40",
        taskName:
          ":modeljars-catalog:publishMarkerQwen306bQ40PublicationToReleaseBundleRepository",
      },
    ],
    removed: [],
  });
});

test("requires a new coordinate when immutable marker metadata changes", () => {
  const before = model();
  const after = model({ description: "New model description" });

  assert.throws(
    () => catalogPublicationDelta(catalog([before]), catalog([after])),
    /changed without a new markerCoordinate/,
  );
});

test("records catalog publication time without republishing an immutable marker", () => {
  const before = model();
  const after = model({ catalogPublishedAt: "2026-08-31T02:17:48Z" });

  assert.deepEqual(
    catalogPublicationDelta(catalog([before]), catalog([after])),
    { publications: [], removed: [] },
  );
});

test("requires a new coordinate when a model performance profile changes", () => {
  const entry = model();
  const beforeProfile = {
    id: "qwen3_linux_jdk25",
    modelId: entry.id,
    markerCoordinate: entry.markerCoordinate,
    recommendations: { "models.purejava.q4Kernel": "baseline" },
  };
  const afterProfile = {
    ...beforeProfile,
    recommendations: { "models.purejava.q4Kernel": "unsigned-pairwise" },
  };

  assert.throws(
    () =>
      catalogPublicationDelta(catalog([entry]), catalog([entry]), {
        previousProfiles: profiles([beforeProfile]),
        currentProfiles: profiles([afterProfile]),
      }),
    /changed without a new markerCoordinate/,
  );
});

test("publishes a new model version with its updated performance profile", () => {
  const before = model();
  const after = model({
    markerCoordinate:
      "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.2",
  });
  const beforeProfile = {
    id: "qwen3_linux_jdk25",
    modelId: before.id,
    markerCoordinate: before.markerCoordinate,
    recommendations: { "models.purejava.q4Kernel": "baseline" },
  };
  const afterProfile = {
    ...beforeProfile,
    markerCoordinate: after.markerCoordinate,
    recommendations: { "models.purejava.q4Kernel": "unsigned-pairwise" },
  };

  const delta = catalogPublicationDelta(catalog([before]), catalog([after]), {
    previousProfiles: profiles([beforeProfile]),
    currentProfiles: profiles([afterProfile]),
  });

  assert.equal(delta.publications.length, 1);
  assert.equal(delta.publications[0].coordinate, after.markerCoordinate);
});

test("publishes an updated model once its marker version changes", () => {
  const before = model();
  const after = model({
    description: "New model description",
    markerCoordinate:
      "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.2",
  });

  const delta = catalogPublicationDelta(catalog([before]), catalog([after]));

  assert.equal(delta.publications.length, 1);
  assert.equal(delta.publications[0].change, "updated");
  assert.equal(delta.publications[0].coordinate, after.markerCoordinate);
});

test("keeps one Maven artifact line for every catalog model identity", () => {
  const before = model();
  const after = model({
    markerCoordinate:
      "org.modeljars.huggingface:renamed-model.q4_0:3.0.0-q4_0.2",
  });

  assert.throws(
    () => catalogPublicationDelta(catalog([before]), catalog([after])),
    /must retain its marker groupId and artifactId/,
  );
});

test("ignores catalog ordering and unchanged model metadata", () => {
  const first = model();
  const second = model({
    id: "smollm3_3b_q4_k_m",
    name: "SmolLM3 3B",
    markerCoordinate:
      "org.modeljars.huggingface:ggml-org.smollm3-3b-gguf.q4_k_m:3.0.0-q4_k_m.1",
  });

  assert.deepEqual(
    catalogPublicationDelta(catalog([first, second]), catalog([second, first])),
    { publications: [], removed: [] },
  );
});

test("reports removals without trying to delete immutable repository artifacts", () => {
  const entry = model();

  assert.deepEqual(catalogPublicationDelta(catalog([entry]), catalog([])), {
    publications: [],
    removed: [entry.id],
  });
});

test("rejects duplicate marker coordinates before publication", () => {
  const first = model();
  const second = model({ id: "duplicate_model" });

  assert.throws(
    () => catalogPublicationDelta(catalog([]), catalog([first, second])),
    /Duplicate markerCoordinate/,
  );
});

test("uses the same deterministic Gradle publication task naming as the build", () => {
  assert.deepEqual(publicationTaskName("deepseek_coder_6_7b_instruct_q4_k_m"), {
    githubTaskName:
      ":modeljars-catalog:publishMarkerDeepseekCoder67bInstructQ4KMPublicationToGitHubPackagesRepository",
    jarTaskName: ":modeljars-catalog:markerJarDeepseekCoder67bInstructQ4KM",
    publicationName: "markerDeepseekCoder67bInstructQ4KM",
    taskName:
      ":modeljars-catalog:publishMarkerDeepseekCoder67bInstructQ4KMPublicationToReleaseBundleRepository",
  });
});

test("emits a safe GitHub Actions matrix for independent publication jobs", () => {
  const entry = model();
  const delta = catalogPublicationDelta(catalog([]), catalog([entry]));

  assert.deepEqual(githubPublicationOutputs(delta), {
    count: "1",
    hasPublications: "true",
    matrix: JSON.stringify({
      include: [
        {
          coordinate: entry.markerCoordinate,
          githubTask:
            ":modeljars-catalog:publishMarkerQwen306bQ40PublicationToGitHubPackagesRepository",
          id: entry.id,
          jarPath:
            "modeljars-catalog/build/libs/markers/ggml-org.qwen3-0.6b-gguf.q4_0-3.0.0-q4_0.1.jar",
          jarTask: ":modeljars-catalog:markerJarQwen306bQ40",
          releaseTask:
            ":modeljars-catalog:publishMarkerQwen306bQ40PublicationToReleaseBundleRepository",
        },
      ],
    }),
    removed: "[]",
  });
});

test("emits an empty matrix when a catalog change has nothing to publish", () => {
  assert.deepEqual(
    githubPublicationOutputs({ publications: [], removed: [] }),
    {
      count: "0",
      hasPublications: "false",
      matrix: '{"include":[]}',
      removed: "[]",
    },
  );
});

test("selects exact accepted catalog identities for a manual publication", () => {
  const first = model();
  const second = model({
    id: "smollm3_3b_q4_k_m",
    name: "SmolLM3 3B",
    markerCoordinate:
      "org.modeljars.huggingface:ggml-org.smollm3-3b-gguf.q4_k_m:3.0.0-q4_k_m.1",
  });

  const selection = selectCatalogPublications(
    catalog([first, second]),
    ["smollm3_3b_q4_k_m"],
  );

  assert.equal(selection.publications.length, 1);
  assert.equal(selection.publications[0].id, second.id);
  assert.equal(selection.publications[0].change, "selected");
  assert.deepEqual(selection.removed, []);
});

test("selects every accepted catalog identity for an explicit bootstrap publication", () => {
  const first = model();
  const second = model({
    id: "smollm3_3b_q4_k_m",
    name: "SmolLM3 3B",
    markerCoordinate:
      "org.modeljars.huggingface:ggml-org.smollm3-3b-gguf.q4_k_m:3.0.0-q4_k_m.1",
  });

  const selection = selectCatalogPublications(
    catalog([first, second]),
    ["all"],
  );

  assert.deepEqual(
    selection.publications.map((publication) => publication.id),
    [first.id, second.id],
  );
});

test("publishes only exact artifacts that passed production qualification", () => {
  const qualified = model();
  const candidate = model({
    id: "candidate_model",
    markerCoordinate:
      "org.modeljars.huggingface:example.candidate.q4_k_m:1.0.0-q4_k_m.1",
    sha256: "c".repeat(64),
  });
  const delta = selectCatalogPublications(
    catalog([qualified, candidate]),
    ["all"],
  );

  const filtered = filterQualifiedPublications(
    delta,
    catalog([qualified, candidate]),
    qualifications([
      {
        modelId: qualified.id,
        artifactSha256: qualified.sha256,
        artifactSizeBytes: qualified.sizeBytes,
        qualified: true,
      },
    ]),
  );

  assert.deepEqual(
    filtered.publications.map((publication) => publication.id),
    [qualified.id],
  );
});

test("rejects stale qualification evidence for changed artifact bytes", () => {
  const entry = model();
  const delta = selectCatalogPublications(catalog([entry]), ["all"]);

  assert.throws(
    () =>
      filterQualifiedPublications(
        delta,
        catalog([entry]),
        qualifications([
          {
            modelId: entry.id,
            artifactSha256: "f".repeat(64),
            artifactSizeBytes: entry.sizeBytes,
            qualified: true,
          },
        ]),
      ),
    /qualification SHA-256 does not match/i,
  );
});

test("rejects the all selector when combined with individual model ids", () => {
  assert.throws(
    () =>
      selectCatalogPublications(catalog([model()]), [
        "all",
        "qwen3_0_6b_q4_0",
      ]),
    /all must be used by itself/,
  );
});

test("fails a manual publication when any requested catalog id is unknown", () => {
  assert.throws(
    () => selectCatalogPublications(catalog([model()]), ["missing_model"]),
    /Unknown catalog model id: missing_model/,
  );
});

test("CLI writes a publication matrix to GitHub Actions outputs", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const previous = path.join(directory, "previous.json");
    const current = path.join(directory, "current.json");
    const output = path.join(directory, "github-output");
    await Promise.all([
      writeFile(previous, JSON.stringify(catalog([]))),
      writeFile(current, JSON.stringify(catalog([model()]))),
    ]);

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--previous",
        previous,
        "--current",
        current,
        "--github-output",
        output,
      ],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /qwen3_0_6b_q4_0/);
    const outputs = await readFile(output, "utf8");
    assert.match(outputs, /^count=1$/m);
    assert.match(outputs, /^has_publications=true$/m);
    assert.match(outputs, /^matrix=\{"include":\[/m);
    assert.match(outputs, /^removed=\[\]$/m);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("CLI fails closed when an accepted coordinate would be overwritten", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const previous = path.join(directory, "previous.json");
    const current = path.join(directory, "current.json");
    await Promise.all([
      writeFile(previous, JSON.stringify(catalog([model()]))),
      writeFile(
        current,
        JSON.stringify(
          catalog([model({ description: "Changed without a new version" })]),
        ),
      ),
    ]);

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--previous",
        previous,
        "--current",
        current,
      ],
      { encoding: "utf8" },
    );

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /changed without a new markerCoordinate/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("CLI accepts exact model ids for a protected manual release", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const current = path.join(directory, "current.json");
    const output = path.join(directory, "github-output");
    await writeFile(current, JSON.stringify(catalog([model()])));

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--current",
        current,
        "--ids",
        "qwen3_0_6b_q4_0",
        "--github-output",
        output,
      ],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.match(await readFile(output, "utf8"), /^count=1$/m);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("CLI accepts all for an explicit catalog bootstrap publication", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const current = path.join(directory, "current.json");
    const output = path.join(directory, "github-output");
    const first = model();
    const second = model({
      id: "smollm3_3b_q4_k_m",
      markerCoordinate:
        "org.modeljars.huggingface:ggml-org.smollm3-3b-gguf.q4_k_m:3.0.0-q4_k_m.1",
    });
    await writeFile(current, JSON.stringify(catalog([first, second])));

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--current",
        current,
        "--ids",
        "all",
        "--github-output",
        output,
      ],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.match(await readFile(output, "utf8"), /^count=2$/m);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("CLI includes performance profiles in the immutable publication plan", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const entry = model();
    const previous = path.join(directory, "previous.json");
    const current = path.join(directory, "current.json");
    const previousProfiles = path.join(directory, "previous-profiles.json");
    const currentProfiles = path.join(directory, "current-profiles.json");
    const profile = {
      id: "qwen3_linux_jdk25",
      modelId: entry.id,
      markerCoordinate: entry.markerCoordinate,
      recommendations: { "models.purejava.q4Kernel": "baseline" },
    };
    await Promise.all([
      writeFile(previous, JSON.stringify(catalog([entry]))),
      writeFile(current, JSON.stringify(catalog([entry]))),
      writeFile(previousProfiles, JSON.stringify(profiles([profile]))),
      writeFile(
        currentProfiles,
        JSON.stringify(
          profiles([
            {
              ...profile,
              recommendations: {
                "models.purejava.q4Kernel": "unsigned-pairwise",
              },
            },
          ]),
        ),
      ),
    ]);

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--previous",
        previous,
        "--current",
        current,
        "--previous-profiles",
        previousProfiles,
        "--current-profiles",
        currentProfiles,
      ],
      { encoding: "utf8" },
    );

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /changed without a new markerCoordinate/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("CLI publishes a newly qualified candidate without a spurious version bump", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const entry = model();
    const previous = path.join(directory, "previous.json");
    const current = path.join(directory, "current.json");
    const previousProfiles = path.join(directory, "previous-profiles.json");
    const currentProfiles = path.join(directory, "current-profiles.json");
    const previousQualifications = path.join(
      directory,
      "previous-qualifications.json",
    );
    const currentQualifications = path.join(
      directory,
      "current-qualifications.json",
    );
    const output = path.join(directory, "github-output");
    const profile = {
      id: "qwen3_linux_jdk25",
      modelId: entry.id,
      markerCoordinate: entry.markerCoordinate,
      recommendations: { "models.purejava.q4Kernel": "unsigned-pairwise" },
    };
    await Promise.all([
      writeFile(previous, JSON.stringify(catalog([entry]))),
      writeFile(current, JSON.stringify(catalog([entry]))),
      writeFile(previousProfiles, JSON.stringify(profiles([]))),
      writeFile(currentProfiles, JSON.stringify(profiles([profile]))),
      writeFile(previousQualifications, JSON.stringify(qualifications([]))),
      writeFile(
        currentQualifications,
        JSON.stringify(
          qualifications([
            {
              modelId: entry.id,
              artifactSha256: entry.sha256,
              artifactSizeBytes: entry.sizeBytes,
              qualified: true,
            },
          ]),
        ),
      ),
    ]);

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--previous",
        previous,
        "--current",
        current,
        "--previous-profiles",
        previousProfiles,
        "--current-profiles",
        currentProfiles,
        "--previous-qualifications",
        previousQualifications,
        "--qualifications",
        currentQualifications,
        "--github-output",
        output,
      ],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /"change": "added"/);
    assert.match(await readFile(output, "utf8"), /^count=1$/m);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("CLI publishes a newly tool-qualified candidate using the tool evidence schema", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "modeljars-plan-"));
  try {
    const entry = model({
      id: "needle2",
      markerCoordinate:
        "org.modeljars.huggingface:cactus-compute.needle2-cact.cq2_mixed:2.0.0-cq2_mixed.1",
    });
    const previous = path.join(directory, "previous.json");
    const current = path.join(directory, "current.json");
    const previousQualifications = path.join(
      directory,
      "previous-qualifications.json",
    );
    const currentQualifications = path.join(
      directory,
      "current-qualifications.json",
    );
    const previousToolQualifications = path.join(
      directory,
      "previous-tool-qualifications.json",
    );
    const currentToolQualifications = path.join(
      directory,
      "current-tool-qualifications.json",
    );
    const output = path.join(directory, "github-output");
    await Promise.all([
      writeFile(previous, JSON.stringify(catalog([entry]))),
      writeFile(current, JSON.stringify(catalog([entry]))),
      writeFile(previousQualifications, JSON.stringify(qualifications([]))),
      writeFile(currentQualifications, JSON.stringify(qualifications([]))),
      writeFile(
        previousToolQualifications,
        JSON.stringify(qualifications([])),
      ),
      writeFile(
        currentToolQualifications,
        JSON.stringify(
          qualifications([
            {
              modelId: entry.id,
              artifactSha256: entry.sha256,
              artifactSizeBytes: entry.sizeBytes,
              summary: { qualified: true },
            },
          ]),
        ),
      ),
    ]);

    const result = spawnSync(
      process.execPath,
      [
        path.join(toolsDirectory, "plan-model-publications.mjs"),
        "--previous",
        previous,
        "--current",
        current,
        "--previous-qualifications",
        previousQualifications,
        "--qualifications",
        currentQualifications,
        "--previous-tool-qualifications",
        previousToolQualifications,
        "--tool-qualifications",
        currentToolQualifications,
        "--github-output",
        output,
      ],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /"change": "added"/);
    assert.match(await readFile(output, "utf8"), /^count=1$/m);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("publishes an artifact qualified only by embedding equivalence", () => {
  const embedder = model({
    id: "embedding_model",
    markerCoordinate:
      "org.modeljars.huggingface:example.embedder.q8_0:1.0.0-q8_0.1",
    sha256: "e".repeat(64),
  });
  const delta = selectCatalogPublications(catalog([embedder]), ["all"]);

  const filtered = filterQualifiedPublications(
    delta,
    catalog([embedder]),
    qualifications([]),
    qualifications([
      {
        modelId: embedder.id,
        artifactSha256: embedder.sha256,
        artifactSizeBytes: embedder.sizeBytes,
        qualified: true,
      },
    ]),
  );

  assert.deepEqual(
    filtered.publications.map((publication) => publication.id),
    [embedder.id],
  );
});

test("publishes an artifact qualified only by tool conformance", () => {
  const toolModel = model({
    id: "needle2",
    markerCoordinate:
      "org.modeljars.huggingface:cactus-compute.needle2-cact.cq2_mixed:2.0.0-cq2_mixed.1",
    sha256: "b".repeat(64),
  });
  const delta = selectCatalogPublications(catalog([toolModel]), ["all"]);

  const filtered = filterQualifiedPublications(
    delta,
    catalog([toolModel]),
    qualifications([]),
    qualifications([]),
    qualifications([
      {
        modelId: toolModel.id,
        artifactSha256: toolModel.sha256,
        artifactSizeBytes: toolModel.sizeBytes,
        summary: { qualified: true },
      },
    ]),
  );

  assert.deepEqual(
    filtered.publications.map((publication) => publication.id),
    [toolModel.id],
  );
});

test("publishes an artifact qualified only by reranking evidence", () => {
  const reranker = model({
    id: "minilm_reranker",
    markerCoordinate:
      "org.modeljars.huggingface:example.minilm-reranker.q4_k:1.0.0-q4_k.1",
    sha256: "c".repeat(64),
  });
  const delta = selectCatalogPublications(catalog([reranker]), ["all"]);

  const filtered = filterQualifiedPublications(
    delta,
    catalog([reranker]),
    qualifications([]),
    qualifications([]),
    qualifications([]),
    qualifications([
      {
        modelId: reranker.id,
        artifactSha256: reranker.sha256,
        artifactSizeBytes: reranker.sizeBytes,
        qualified: true,
      },
    ]),
  );

  assert.deepEqual(
    filtered.publications.map((publication) => publication.id),
    [reranker.id],
  );
});

test("publishes an artifact qualified only by speech evidence", () => {
  const speechModel = model({
    id: "soprano_q8",
    markerCoordinate:
      "org.modeljars.huggingface:walkingcat.soprano-1.1-80m-gguf.q8_0:1.1.0-q8_0.1",
    sha256: "d".repeat(64),
    capabilities: ["text-to-speech", "audio-generation", "streaming"],
  });
  const delta = selectCatalogPublications(catalog([speechModel]), ["all"]);

  const filtered = filterQualifiedPublications(
    delta,
    catalog([speechModel]),
    qualifications([]),
    qualifications([]),
    qualifications([]),
    qualifications([]),
    qualifications([
      {
        modelId: speechModel.id,
        artifactSha256: speechModel.sha256,
        artifactSizeBytes: speechModel.sizeBytes,
        qualified: true,
      },
    ]),
  );

  assert.deepEqual(
    filtered.publications.map((publication) => publication.id),
    [speechModel.id],
  );
});

test("withholds an embedding artifact whose evidence did not qualify", () => {
  const embedder = model({
    id: "embedding_model",
    markerCoordinate:
      "org.modeljars.huggingface:example.embedder.q8_0:1.0.0-q8_0.1",
    sha256: "e".repeat(64),
  });
  const delta = selectCatalogPublications(catalog([embedder]), ["all"]);

  const filtered = filterQualifiedPublications(
    delta,
    catalog([embedder]),
    qualifications([]),
    qualifications([
      {
        modelId: embedder.id,
        artifactSha256: embedder.sha256,
        artifactSizeBytes: embedder.sizeBytes,
        qualified: false,
      },
    ]),
  );

  assert.deepEqual(filtered.publications, []);
});

test("rejects stale embedding evidence for changed artifact bytes", () => {
  const embedder = model({
    id: "embedding_model",
    markerCoordinate:
      "org.modeljars.huggingface:example.embedder.q8_0:1.0.0-q8_0.1",
    sha256: "e".repeat(64),
  });
  const delta = selectCatalogPublications(catalog([embedder]), ["all"]);

  assert.throws(
    () =>
      filterQualifiedPublications(
        delta,
        catalog([embedder]),
        qualifications([]),
        qualifications([
          {
            modelId: embedder.id,
            artifactSha256: "f".repeat(64),
            artifactSizeBytes: embedder.sizeBytes,
            qualified: true,
          },
        ]),
      ),
    /SHA-256 does not match/,
  );
});

test("treats absent embedding evidence as no additional publications", () => {
  const entry = model();
  const delta = selectCatalogPublications(catalog([entry]), ["all"]);

  const filtered = filterQualifiedPublications(
    delta,
    catalog([entry]),
    qualifications([
      {
        modelId: entry.id,
        artifactSha256: entry.sha256,
        artifactSizeBytes: entry.sizeBytes,
        qualified: true,
      },
    ]),
  );

  assert.deepEqual(
    filtered.publications.map((publication) => publication.id),
    [entry.id],
  );
});
