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
package org.modeljars;

import com.integrallis.models.api.catalog.DiscoveredModel;
import com.integrallis.models.api.catalog.ModelCatalogProvider;
import com.integrallis.models.backend.purejava.plan.RuntimeFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports the ModelJars a machine can actually run, so callers do not describe them by hand.
 *
 * <p>Every figure here was measured rather than declared. Throughput comes from the performance
 * profile whose selector matches this CPU, core count, JDK and vector width; quality comes from
 * task-specific qualifications; size and context come from the artifact. A caller writing these out
 * would be guessing, and the guesses would be about somebody else's hardware.
 *
 * <p>Only cached artifacts are reported. A catalogued model that has not been downloaded could be
 * installed on demand, but a router picking one would trigger a multi-gigabyte download inside what
 * the caller thinks is a routing decision.
 */
public final class ModelJarsCatalog implements ModelCatalogProvider {

  /** Metric key for latency, as recorded by the qualification harness. */
  private static final String TTFT_METRIC = "medianTtftMillis";

  /** Metric key for sustained generation rate. */
  private static final String DECODE_METRIC = "medianDecodeTokensPerSecond";

  /**
   * ModelJars capabilities mapped to the task labels the router classifies into.
   *
   * <p>Deliberately incomplete. Nothing in the catalogue vocabulary corresponds to summarization,
   * extraction or creative writing, and inventing a mapping would tag models for work nobody
   * measured them on. A model whose capabilities produce no tags is left untagged, which the router
   * already treats as general purpose — the honest answer for a general text-generation model.
   */
  private static final Map<String, String> TASK_TAGS =
      Map.ofEntries(
          Map.entry("code-generation", "code"),
          Map.entry("code-completion", "code"),
          Map.entry("fim", "code"),
          Map.entry("sql-generation", "sql"),
          Map.entry("text-to-sql", "sql"),
          Map.entry("math", "math"),
          Map.entry("reasoning", "reasoning"),
          Map.entry("financial-reasoning", "reasoning"),
          Map.entry("medical-reasoning", "reasoning"),
          Map.entry("tool-integrated-reasoning", "reasoning"),
          Map.entry("translation", "translation"),
          Map.entry("multilingual", "translation"),
          Map.entry("bilingual", "translation"),
          Map.entry("chat", "chat"),
          Map.entry("tool-calling", "tool-use"));

  private final ModelJarRegistry models;
  private final ModelRagQualificationRegistry qualifications;
  private final ModelToolQualificationRegistry toolQualifications;
  private final ModelPerformanceProfileRegistry profiles;
  private final Map<String, String> runtime;

  /** Creates a catalog over the ModelJars declared on the classpath. */
  public ModelJarsCatalog() {
    this(
        ModelJarRegistry.fromClasspath(),
        ModelRagQualificationRegistry.fromClasspath(),
        ModelToolQualificationRegistry.fromClasspath(),
        ModelPerformanceProfileRegistry.fromClasspath(),
        currentRuntime());
  }

  /**
   * Describes this machine for profile matching, or nothing when it cannot be described.
   *
   * <p>Fingerprinting reaches into the incubating Vector API, which a JVM started without {@code
   * --add-modules jdk.incubator.vector} cannot link. That surfaces as NoClassDefFoundError — an
   * Error, not an exception — so it would escape a caller catching RuntimeException and take down
   * discovery for every other catalog too.
   *
   * <p>An empty map simply matches no profile, which leaves throughput to the router's estimator.
   * Losing measured figures is a far smaller loss than losing the whole fleet.
   */
  private static Map<String, String> currentRuntime() {
    try {
      return RuntimeFingerprint.capture().asEnvironment();
    } catch (RuntimeException | LinkageError e) {
      return Map.of();
    }
  }

  ModelJarsCatalog(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelPerformanceProfileRegistry profiles,
      Map<String, String> runtime) {
    this(models, qualifications, ModelToolQualificationRegistry.fromClasspath(), profiles, runtime);
  }

  ModelJarsCatalog(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelToolQualificationRegistry toolQualifications,
      ModelPerformanceProfileRegistry profiles,
      Map<String, String> runtime) {
    this.models = models;
    this.qualifications = qualifications;
    this.toolQualifications = toolQualifications;
    this.profiles = profiles;
    this.runtime = Map.copyOf(runtime);
  }

  @Override
  public String name() {
    return "modeljars";
  }

  @Override
  public List<DiscoveredModel> discover() {
    List<DiscoveredModel> discovered = new ArrayList<>();
    for (ModelJarDescriptor descriptor : models.descriptors()) {
      if (!"gguf".equals(descriptor.format())
          && !"cact".equals(descriptor.format())
          && !("safetensors".equals(descriptor.format()) && !descriptor.files().isEmpty())) {
        continue;
      }
      if (!descriptor.capabilities().contains("text-generation")) {
        // Embedding models have no per-task generation quality and nothing sensible to route to.
        continue;
      }
      if (!cached(descriptor)) {
        continue;
      }
      Optional<Integer> contextLength = descriptor.dimensions().contextLength();
      if (contextLength.isEmpty()) {
        continue;
      }
      Set<String> tags = tagsFor(descriptor);
      discovered.add(
          new DiscoveredModel(
              descriptor.alias(),
              true,
              tags,
              tags,
              contextLength.get(),
              0.0,
              0.0,
              descriptor.sizeBytes().orElse(0L),
              performanceFor(descriptor),
              qualityFor(descriptor, tags),
              1.0,
              // The catalog's own feature set, passed through rather than reinterpreted. It mixes
              // artifact facts (`q4-k-m`, `verified-sha256`), architecture (`grouped-query-
              // attention`) and routing-relevant properties (`long-context`,
              // `noncommercial-research-license`). Deciding which of those matter is the
              // application's job, not this provider's, so all of them are reported.
              descriptor.features()));
    }
    return List.copyOf(discovered);
  }

  /**
   * Where this machine already holds the weights for a catalogued model.
   *
   * <p>The catalog has always reported <em>what</em> is installed; this reports <em>where</em>, so
   * a caller that resolved a model through the catalog no longer has to name a file the catalog
   * already knew. {@code models} opens exactly this path with its own decoder.
   *
   * <p>It returns the same path {@link ModelJars} itself would open, deliberately. {@code
   * ModelJars.cachePath} hands {@link ModelJarCache#artifactPath} straight to the backend for every
   * supported format, so a second notion of "the loadable path" here would mean this catalog could
   * send a caller to one file while the library's own loader used another.
   *
   * <p><b>Nothing is downloaded.</b> An uninstalled model is {@link Optional#empty()}, which is
   * what the SPI requires: a lookup must not turn into a multi-gigabyte fetch.
   *
   * @param id the model alias, as {@link #discover()} reports it
   * @return the artifact path when it is installed, otherwise empty
   */
  @Override
  public Optional<Path> locate(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    for (ModelJarDescriptor descriptor : models.descriptors()) {
      if (!id.equals(descriptor.alias()) || !loadableFormat(descriptor)) {
        continue;
      }
      Optional<Path> installed = installedPath(descriptor);
      if (installed.isPresent()) {
        return installed;
      }
    }
    return Optional.empty();
  }

  /**
   * The formats a Models backend can open, as {@code ModelJars.cachePath} defines them.
   *
   * <p>Checked here so this method never reports a path the loader would then refuse. A safetensors
   * marker with no file list has no artifact to point at.
   */
  private static boolean loadableFormat(ModelJarDescriptor descriptor) {
    return "gguf".equals(descriptor.format())
        || "cact".equals(descriptor.format())
        || ("safetensors".equals(descriptor.format()) && !descriptor.files().isEmpty());
  }

  private boolean cached(ModelJarDescriptor descriptor) {
    return installedPath(descriptor).isPresent();
  }

  /**
   * The installed artifact path, or empty when the model is not on this machine.
   *
   * <p>Both answers are loadable: a marker's configured {@code localPath} is what {@code
   * ModelJarLocator.requireLocalPath} opens, and the content-addressed cache path is what {@code
   * ModelJars} opens. Keeping one method behind both {@link #cached} and {@link #locate} is what
   * makes "reported by discover" and "locatable" the same set -- two predicates would eventually
   * disagree, and a model the catalog advertised but could not locate would look like a defect in
   * whichever caller hit it.
   */
  private Optional<Path> installedPath(ModelJarDescriptor descriptor) {
    Optional<Path> local = descriptor.localPath().filter(Files::isRegularFile);
    if (local.isPresent()) {
      return local;
    }
    try {
      Path artifact = ModelJarCache.artifactPath(descriptor);
      return ModelJarCache.isComplete(descriptor, artifact) ? Optional.of(artifact) : Optional.empty();
    } catch (RuntimeException e) {
      // A descriptor without a digest cannot be content-addressed, so it is not in the cache.
      return Optional.empty();
    }
  }

  private Set<String> tagsFor(ModelJarDescriptor descriptor) {
    Set<String> tags = new LinkedHashSet<>();
    for (String capability : descriptor.capabilities()) {
      String tag = TASK_TAGS.get(capability);
      if (tag != null) {
        tags.add(tag);
      }
    }
    return tags;
  }

  /**
   * The measured profile for this machine, or null when none matches.
   *
   * <p>Null rather than the nearest profile. These figures vary with CPU, core count and JVM, so
   * one measured elsewhere is not a measurement of this machine — and the router estimates from
   * peers it did measure here, which beats a number taken from different hardware.
   */
  private DiscoveredModel.Performance performanceFor(ModelJarDescriptor descriptor) {
    for (ModelRagQualification qualification : qualifications.qualificationsFor(descriptor)) {
      for (ModelPerformanceProfile profile :
          profiles.matching(descriptor, qualification.backend(), runtime)) {
        Map<String, Double> metrics = profile.evidence().candidateMetrics();
        Double ttft = metrics.get(TTFT_METRIC);
        Double decode = metrics.get(DECODE_METRIC);
        if (ttft != null && decode != null && ttft >= 0 && decode > 0) {
          return new DiscoveredModel.Performance(Math.max(1L, Math.round(ttft)), decode);
        }
      }
    }
    return null;
  }

  /**
   * Task-specific measurements only. RAG answer correctness does not measure general chat, coding,
   * reasoning, SQL or translation, and is not transferred to those router task labels. Missing
   * measurements remain absent so the router can distinguish unknown from measured zero.
   *
   * <p>The {@code grounding} score is the model's own correctness, {@code rawCorrectAnswerRate},
   * and only for an artifact the policy placed in {@link RagUseCaseTier#GENERATIVE_RAG}. That tier
   * is defined as the model answering directly while holding the benchmark's bar; {@link
   * RagUseCaseTier#GUARDED_RAG} is defined as production-usable <em>with</em> the grounding and
   * extractive fallback policy, which is a statement about a pipeline and not about the model the
   * router hands a prompt to. Reporting a guarded artifact's pipeline correctness as its {@code
   * grounding} quality would describe work the router does not control: MEASURED 2026-10-20, a
   * GUARDED_RAG artifact whose pipeline scores 1.0 and whose raw rate is 0.111 was wired into a
   * fleet without the guard, declared at 0.70 by hand, and answered 23 points below a frontier
   * model on HotpotQA while producing none of 50 required abstentions.
   *
   * <p>So a guarded artifact gets no {@code grounding} score at all, and carries the {@code
   * rag-guard-required} feature instead, which an application can exclude when it does not run the
   * guard or require when it does.
   */
  Map<String, Double> qualityFor(ModelJarDescriptor descriptor, Set<String> tags) {
    Map<String, Double> quality = new HashMap<>();
    for (ModelRagQualification qualification : qualifications.qualificationsFor(descriptor)) {
      if (qualification.useCaseTier() == RagUseCaseTier.GENERATIVE_RAG) {
        quality.merge("grounding", qualification.rawCorrectAnswerRate(), Math::max);
      }
    }
    if (tags.contains("tool-use")) {
      for (ModelToolQualification qualification :
          toolQualifications.qualificationsFor(descriptor)) {
        if (qualification.qualified()) {
          double conformance =
              Math.min(
                  Math.min(
                      qualification.toolSelectionExactRate(), qualification.schemaValidityRate()),
                  Math.min(
                      qualification.expectedArgumentAccuracy(), qualification.refusalAccuracy()));
          quality.merge("tool-use", conformance, Math::max);
        }
      }
    }
    return Map.copyOf(quality);
  }
}
