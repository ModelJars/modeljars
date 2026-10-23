#!/usr/bin/env node
/**
 * Exercise published catalog models end to end, resolving everything from Maven Central.
 *
 * This runs AFTER a catalog deployment, not before, and that is the point. Every gate that runs
 * before publication measures the artifact against the library in this repository. None of them
 * can see the one failure that only exists once something is published: a marker on Central that
 * no released library can use.
 *
 * MEASURED 2026-10-23. laya_q8_0 was published to Central carrying
 * decision-qualifications-v1.properties, while the newest released modeljars-core had no
 * ModelDecisionQualificationRegistry to read it and ModelJars.openRuntime failed inside the loader
 * instead of refusing. Every pre-publication gate was green, because each built against this
 * repository rather than against the release. A consumer resolving only from Central would have
 * failed immediately -- so that is what this does.
 *
 * It writes a JBang program per model, with the published marker coordinate and the published
 * library version as its only dependencies, and runs it. Nothing from this checkout is on the
 * classpath. The weights are fetched by ModelJars on first open, which is the act a user performs.
 *
 * Usage:
 *   node tools/released-catalog-e2e.mjs --modeljars-version 0.1.65 [--ids a,b | --all]
 *                                      [--output DIR] [--prune-weights]
 *
 * With neither --ids nor --all it picks the smallest published artifact per capability, which
 * bounds the download while still exercising every entry point ModelJars exposes.
 */
import { execFile } from "node:child_process";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const run = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

/**
 * One capability, the ModelJars entry point that serves it, and a call that proves it ran.
 *
 * Keyed on the catalog's own capability strings. A model is tested through the entry point its
 * capability names, because "it loaded" is not the claim the catalog makes about it -- openRuntime
 * on an embedding model, or on a typed-decision model, is a different failure than a broken one.
 */
const CAPABILITY_PROBES = {
  "text-generation": {
    entry: "openRuntime",
    body: `try (var runtime = ModelJars.openRuntime(MODEL)) {
      var reply = runtime.model().generate(ModelPrompt.ofUser("Reply with the single word: ready"),
          SamplingOptions.greedy().withMaxTokens(16));
      require(reply != null && !reply.text().isBlank(), "generated no text");
      System.out.println("PASS generate: " + reply.text().strip().replace('\\n', ' '));
    }`,
    imports: ["com.integrallis.models.api.ModelPrompt", "com.integrallis.models.api.SamplingOptions"],
  },
  "text-embedding": {
    entry: "openEmbeddingRuntime",
    body: `try (var runtime = ModelJars.openEmbeddingRuntime(MODEL)) {
      var vector = runtime.model().embed("a sentence to embed");
      require(vector.length > 0, "embedded to an empty vector");
      double norm = 0; for (float v : vector) norm += (double) v * v;
      require(norm > 0, "embedded to an all-zero vector");
      System.out.println("PASS embed: " + vector.length + " dimensions");
    }`,
    imports: [],
  },
  reranking: {
    entry: "openRerankingRuntime",
    body: `try (var runtime = ModelJars.openRerankingRuntime(MODEL)) {
      var ranked = runtime.model().rerank("how many people live in Berlin?",
          java.util.List.of("Berlin has 3.5 million inhabitants.", "Cats sleep a lot."));
      require(!ranked.isEmpty(), "reranked to an empty list");
      System.out.println("PASS rerank: " + ranked.size() + " documents ordered");
    }`,
    imports: [],
  },
  "text-to-speech": {
    entry: "openSpeechRuntime",
    body: `try (var runtime = ModelJars.openSpeechRuntime(MODEL)) {
      var audio = runtime.model().synthesize("ready");
      require(audio != null, "synthesized nothing");
      System.out.println("PASS synthesize: " + audio.sampleRate() + " Hz");
    }`,
    imports: [],
  },
  "typed-decision": {
    entry: "install + ModernBertDecisionModel.open",
    // Deliberately not openRuntime: a typed-decision model has no generative runtime, and
    // requireGenerativeModel refuses it by design. The published route is locate() plus the
    // decoder's own entry point, so that is the route under test.
    body: `// install() fetches and verifies; locate() only reports what is already cached, so a
    // probe built on locate alone fails on any host with a cold cache -- which a CI runner always
    // is. Both are exercised: install to get the weights, locate to prove the catalog can find
    // them afterwards, which is the call an application actually uses.
    var weights = new ModelJarInstaller(ModelJarRegistry.fromClasspath()).install(MODEL);
    require(java.nio.file.Files.isRegularFile(weights), "install() returned no file");
    var located = new ModelJarsCatalog().locate(MODEL_ID);
    require(located.isPresent(), "locate() reported nothing after install");
    require(located.orElseThrow().equals(weights), "locate() and install() disagree on the path");
    try (var model = ModernBertDecisionModel.open(located.orElseThrow())) {
      var answer = model.decide(QuestionType.NOUL, "Is this sentence in English?",
          java.util.List.of(ModernBertDecisionPrompt.Option.of("yes"),
                            ModernBertDecisionPrompt.Option.of("no")),
          "This sentence is written in English.");
      require(answer.probabilities().length == 2, "expected two option probabilities");
      System.out.println("PASS decide: chose " + answer.chosen()
          + " bucket " + answer.temperatureBucket()
          + " uncalibrated " + answer.uncalibrated());
    }
    try {
      ModelJars.openRuntime(MODEL);
      throw new IllegalStateException("openRuntime must refuse a typed-decision model");
    } catch (ModelJarException expected) {
      require(expected.getMessage().contains("not a text generator"),
          "refusal did not name the reason: " + expected.getMessage());
      System.out.println("PASS openRuntime refuses it and names the working call");
    }`,
    imports: [
      "com.integrallis.models.backend.purejava.bert.ModernBertConfig.QuestionType",
      "com.integrallis.models.backend.purejava.bert.ModernBertDecisionModel",
      "com.integrallis.models.backend.purejava.bert.ModernBertDecisionPrompt",
      "org.modeljars.ModelJarsCatalog",
      "org.modeljars.ModelJarException",
      "org.modeljars.ModelJarInstaller",
      "org.modeljars.ModelJarRegistry",
    ],
  },
};

function parseArguments(argv) {
  const values = new Map();
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--all" || argv[i] === "--keep") {
      values.set(argv[i], true);
    } else if (argv[i].startsWith("--")) {
      values.set(argv[i], argv[i + 1]);
      i += 1;
    }
  }
  return values;
}

/** Published models only: an entry without catalogPublishedAt is not on Central to be pulled. */
function published(models) {
  return models.filter((model) => typeof model.catalogPublishedAt === "string");
}

function probeFor(model) {
  for (const capability of model.capabilities || []) {
    if (CAPABILITY_PROBES[capability]) return [capability, CAPABILITY_PROBES[capability]];
  }
  return [null, null];
}

/** Smallest published artifact per capability: every entry point, bounded download. */
function smallestPerCapability(models) {
  const chosen = new Map();
  for (const model of models) {
    const [capability] = probeFor(model);
    if (!capability) continue;
    const size = Number(model.sizeBytes || Number.MAX_SAFE_INTEGER);
    const incumbent = chosen.get(capability);
    if (!incumbent || size < Number(incumbent.sizeBytes || Number.MAX_SAFE_INTEGER)) {
      chosen.set(capability, model);
    }
  }
  return [...chosen.values()];
}

function program(model, capability, probe, modeljarsVersion) {
  const imports = ["org.modeljars.ModelJars", "org.modeljars.ModelJar", ...probe.imports];
  return `//JAVA 25
//RUNTIME_OPTIONS --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx6g
//DEPS org.modeljars:modeljars:${modeljarsVersion}
//DEPS ${model.markerCoordinate}

${[...new Set(imports)].map((i) => `import ${i};`).join("\n")}

/** ${model.id}: ${capability}, through ${probe.entry}, resolved only from Maven Central. */
public class e2e {
  static final String MODEL_ID = "${model.id}";
  static final ModelJar MODEL = ModelJar.of("${model.markerCoordinate}");

  static void require(boolean condition, String what) {
    if (!condition) throw new IllegalStateException(what);
  }

  public static void main(String[] args) throws Exception {
    System.out.println("model " + MODEL_ID + " capability ${capability}");
    ${probe.body}
    System.out.println("RESULT: PASS " + MODEL_ID);
  }
}
`;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const modeljarsVersion = options.get("--modeljars-version");
  if (!/^\d+\.\d+\.\d+$/.test(modeljarsVersion || "")) {
    throw new Error("--modeljars-version must be a released MAJOR.MINOR.PATCH");
  }
  const output = path.resolve(options.get("--output") || path.join(ROOT, "build/released-e2e"));
  const catalog = JSON.parse(await readFile(path.join(ROOT, "catalog/models.json"), "utf8"));
  const all = published(catalog.models);

  let targets;
  if (options.get("--ids")) {
    const wanted = options.get("--ids").split(",").map((id) => id.trim()).filter(Boolean);
    targets = wanted.map((id) => {
      const model = all.find((entry) => entry.id === id);
      if (!model) throw new Error(`${id} is not a published catalog model`);
      return model;
    });
  } else if (options.get("--all")) {
    targets = all.filter((model) => probeFor(model)[0]);
  } else {
    targets = smallestPerCapability(all);
  }
  if (targets.length === 0) throw new Error("no published model matched");

  await mkdir(output, { recursive: true });
  const results = [];
  for (const model of targets) {
    const [capability, probe] = probeFor(model);
    if (!probe) {
      results.push({ id: model.id, status: "SKIPPED", reason: "no probe for its capabilities" });
      continue;
    }
    const dir = path.join(output, model.id);
    await mkdir(dir, { recursive: true });
    const source = path.join(dir, "e2e.java");
    await writeFile(source, program(model, capability, probe, modeljarsVersion));
    const started = Date.now();
    try {
      const { stdout, stderr } = await run("jbang", [source], {
        maxBuffer: 64 * 1024 * 1024,
        env: { ...process.env },
      });
      await writeFile(path.join(dir, "run.log"), stdout + stderr);
      const passed = stdout.includes(`RESULT: PASS ${model.id}`);
      results.push({
        id: model.id,
        capability,
        entry: probe.entry,
        status: passed ? "PASS" : "FAIL",
        seconds: Math.round((Date.now() - started) / 1000),
      });
      process.stdout.write(`${passed ? "PASS" : "FAIL"} ${model.id} (${capability})\n`);
    } catch (failure) {
      await writeFile(
        path.join(dir, "run.log"),
        `${failure.stdout || ""}${failure.stderr || ""}\n${failure.message}`,
      );
      results.push({ id: model.id, capability, status: "FAIL", error: failure.message.split("\n")[0] });
      process.stdout.write(`FAIL ${model.id} (${capability}): ${failure.message.split("\n")[0]}\n`);
    }
    // Reclaim the weights between models, because a 6 GB artifact each fills a runner.
    //
    // OPT IN, never by default. The first version of this deleted ~/.jvllm/models unless --keep
    // was passed, and running it once on a developer machine destroyed a 67 GB cache of 43 models
    // -- re-downloadable, but hours of transfer, and nothing warned first. A throwaway runner is
    // the only place a cache is disposable, so the runner says so with --prune-weights and every
    // other host keeps what it has.
    if (options.get("--prune-weights")) {
      const cache = path.join(process.env.HOME || "", ".jvllm/models");
      if (!process.env.CI) {
        throw new Error(
          `--prune-weights deletes ${cache} and is intended for a throwaway CI runner; `
            + "refusing because CI is not set",
        );
      }
      await rm(cache, { recursive: true, force: true });
    }
  }

  await writeFile(path.join(output, "results.json"), `${JSON.stringify(results, null, 2)}\n`);
  const failed = results.filter((r) => r.status === "FAIL");
  process.stdout.write(
    `\n${results.length} published model(s) exercised from Central, ${failed.length} failed\n`,
  );
  if (failed.length > 0) {
    for (const row of failed) process.stdout.write(`  ${row.id}: ${row.error || "no PASS line"}\n`);
    process.exitCode = 1;
  }
}

await main();
