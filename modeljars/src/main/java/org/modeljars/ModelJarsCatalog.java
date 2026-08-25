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
 * profile whose selector matches this CPU, core count, JDK and vector width; quality comes from the
 * RAG qualification; size and context come from the artifact. A caller writing these out would be
 * guessing, and the guesses would be about somebody else's hardware.
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
  private final ModelPerformanceProfileRegistry profiles;
  private final Map<String, String> runtime;

  /** Creates a catalog over the ModelJars declared on the classpath. */
  public ModelJarsCatalog() {
    this(
        ModelJarRegistry.fromClasspath(),
        ModelRagQualificationRegistry.fromClasspath(),
        ModelPerformanceProfileRegistry.fromClasspath(),
        currentRuntime());
  }

  /**
   * Describes this machine for profile matching, or nothing when it cannot be described.
   *
   * <p>Fingerprinting reaches into the incubating Vector API, which a JVM started without
   * {@code --add-modules jdk.incubator.vector} cannot link. That surfaces as NoClassDefFoundError —
   * an Error, not an exception — so it would escape a caller catching RuntimeException and take
   * down discovery for every other catalog too.
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
    this.models = models;
    this.qualifications = qualifications;
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
              contextLength.get(),
              0.0,
              0.0,
              descriptor.sizeBytes().orElse(0L),
              performanceFor(descriptor),
              qualityFor(descriptor, tags),
              1.0));
    }
    return List.copyOf(discovered);
  }

  private boolean cached(ModelJarDescriptor descriptor) {
    if (descriptor.localPath().filter(Files::isRegularFile).isPresent()) {
      return true;
    }
    try {
      Path artifact = ModelJarCache.artifactPath(descriptor);
      return ModelJarCache.isComplete(descriptor, artifact);
    } catch (RuntimeException e) {
      // A descriptor without a digest cannot be content-addressed, so it is not in the cache.
      return false;
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
   * Measured competence, applied to every task the model claims.
   *
   * <p>Reads {@code rawCorrectAnswerRate} rather than {@code correctAnswerRate}. The latter is
   * policy-adjusted — a correct refusal to answer counts as correct — and is 1.0 for every model in
   * the catalogue, so it cannot rank anything. The raw rate spreads from 0.0 to 0.78 across the same
   * models, which is the difference between a quality dimension that discriminates and one that
   * silently tells the router every model is perfect.
   *
   * <p>One RAG question-answering workload is a poor proxy for a code or SQL specialist, so this
   * understates them. That is the safe direction: an understated model loses ties to models with
   * genuinely measured per-task quality, where an overstated one would win work it cannot do. Real
   * per-task figures need per-task qualification.
   */
  private Map<String, Double> qualityFor(ModelJarDescriptor descriptor, Set<String> tags) {
    double best = 0.0;
    for (ModelRagQualification qualification : qualifications.qualificationsFor(descriptor)) {
      if (qualification.qualified()) {
        best = Math.max(best, qualification.rawCorrectAnswerRate());
      }
    }
    if (best <= 0.0 || tags.isEmpty()) {
      return Map.of();
    }
    Map<String, Double> quality = new HashMap<>();
    for (String tag : tags) {
      quality.put(tag, best);
    }
    return quality;
  }
}
