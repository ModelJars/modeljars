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

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.EmbeddingBackend;
import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.OptimizationDecision;
import com.integrallis.models.api.OptimizationStatus;
import com.integrallis.models.api.Pooling;
import com.integrallis.models.api.RerankingModel;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import com.integrallis.models.backend.purejava.GgufEmbeddingBackend;
import com.integrallis.models.backend.purejava.GgufRerankingModel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.plan.RuntimeFingerprint;
import com.integrallis.models.runtime.InferencePipeline;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Resolves, verifies, configures, and opens qualified models from ModelJars metadata.
 *
 * <p>Returned models and runtimes own their inference backend and must be closed.
 */
public final class ModelJars {
  private static final String JAVA_BACKEND = "pure-java";
  private static final String NATIVE_BACKEND = "rust-ffm";

  private final ModelJarRegistry models;
  private final ModelRagQualificationRegistry qualifications;
  private final ModelToolQualificationRegistry toolQualifications;
  private final ModelEmbeddingQualificationRegistry embeddingQualifications;
  private final ModelRerankingQualificationRegistry rerankingQualifications;
  private final ModelPerformanceProfileRegistry profiles;
  private final ArtifactInstaller installer;
  private final BackendLoader backendLoader;
  private final EmbeddingBackendLoader embeddingBackendLoader;
  private final RerankingModelLoader rerankingModelLoader;
  private final Supplier<Map<String, String>> runtimeEnvironment;
  private final Supplier<List<String>> jvmArguments;

  ModelJars(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelPerformanceProfileRegistry profiles,
      ArtifactInstaller installer,
      BackendLoader backendLoader,
      Supplier<Map<String, String>> runtimeEnvironment) {
    this(
        models,
        qualifications,
        ModelEmbeddingQualificationRegistry.fromClasspath(),
        profiles,
        installer,
        backendLoader,
        ModelJars::loadEmbeddingBackend,
        runtimeEnvironment,
        () -> ManagementFactory.getRuntimeMXBean().getInputArguments());
  }

  ModelJars(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelPerformanceProfileRegistry profiles,
      ArtifactInstaller installer,
      BackendLoader backendLoader,
      Supplier<Map<String, String>> runtimeEnvironment,
      Supplier<List<String>> jvmArguments) {
    this(
        models,
        qualifications,
        ModelEmbeddingQualificationRegistry.fromClasspath(),
        profiles,
        installer,
        backendLoader,
        ModelJars::loadEmbeddingBackend,
        runtimeEnvironment,
        jvmArguments);
  }

  ModelJars(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelEmbeddingQualificationRegistry embeddingQualifications,
      ModelPerformanceProfileRegistry profiles,
      ArtifactInstaller installer,
      BackendLoader backendLoader,
      EmbeddingBackendLoader embeddingBackendLoader,
      Supplier<Map<String, String>> runtimeEnvironment,
      Supplier<List<String>> jvmArguments) {
    this(
        models,
        qualifications,
        ModelToolQualificationRegistry.fromClasspath(),
        embeddingQualifications,
        ModelRerankingQualificationRegistry.fromClasspath(),
        profiles,
        installer,
        backendLoader,
        embeddingBackendLoader,
        ModelJars::loadRerankingModel,
        runtimeEnvironment,
        jvmArguments);
  }

  ModelJars(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelToolQualificationRegistry toolQualifications,
      ModelEmbeddingQualificationRegistry embeddingQualifications,
      ModelRerankingQualificationRegistry rerankingQualifications,
      ModelPerformanceProfileRegistry profiles,
      ArtifactInstaller installer,
      BackendLoader backendLoader,
      EmbeddingBackendLoader embeddingBackendLoader,
      RerankingModelLoader rerankingModelLoader,
      Supplier<Map<String, String>> runtimeEnvironment,
      Supplier<List<String>> jvmArguments) {
    this.models = Objects.requireNonNull(models, "models");
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications");
    this.toolQualifications = Objects.requireNonNull(toolQualifications, "toolQualifications");
    this.embeddingQualifications =
        Objects.requireNonNull(embeddingQualifications, "embeddingQualifications");
    this.rerankingQualifications =
        Objects.requireNonNull(rerankingQualifications, "rerankingQualifications");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.installer = Objects.requireNonNull(installer, "installer");
    this.backendLoader = Objects.requireNonNull(backendLoader, "backendLoader");
    this.embeddingBackendLoader =
        Objects.requireNonNull(embeddingBackendLoader, "embeddingBackendLoader");
    this.rerankingModelLoader =
        Objects.requireNonNull(rerankingModelLoader, "rerankingModelLoader");
    this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment");
    this.jvmArguments = Objects.requireNonNull(jvmArguments, "jvmArguments");
  }

  ModelJars(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelToolQualificationRegistry toolQualifications,
      ModelEmbeddingQualificationRegistry embeddingQualifications,
      ModelPerformanceProfileRegistry profiles,
      ArtifactInstaller installer,
      BackendLoader backendLoader,
      EmbeddingBackendLoader embeddingBackendLoader,
      Supplier<Map<String, String>> runtimeEnvironment,
      Supplier<List<String>> jvmArguments) {
    this(
        models,
        qualifications,
        toolQualifications,
        embeddingQualifications,
        ModelRerankingQualificationRegistry.fromClasspath(),
        profiles,
        installer,
        backendLoader,
        embeddingBackendLoader,
        ModelJars::loadRerankingModel,
        runtimeEnvironment,
        jvmArguments);
  }

  /**
   * Opens a qualified model using automatic backend selection and the default cache.
   *
   * @param model immutable model selector or generated catalog reference
   * @return ready-to-use text generation model
   */
  public static TextGenerationModel open(ModelJar model) {
    return openRuntime(model).model();
  }

  /**
   * Opens a qualified model with explicit loading controls.
   *
   * @param model immutable model selector or generated catalog reference
   * @param options backend, cache, and network controls
   * @return ready-to-use text generation model
   */
  public static TextGenerationModel open(ModelJar model, ModelLoadOptions options) {
    return openRuntime(model, options).model();
  }

  /**
   * Opens a qualified model and exposes its qualified chat template and evidence.
   *
   * @param model immutable model selector or generated catalog reference
   * @return loaded model runtime with qualification-owned prompt metadata
   */
  public static ModelJarRuntime openRuntime(ModelJar model) {
    return openRuntime(model, ModelLoadOptions.defaults());
  }

  /**
   * Opens a qualified model with explicit loading controls and qualification-owned prompt metadata.
   *
   * @param model immutable model selector or generated catalog reference
   * @param options backend, cache, and network controls
   * @return loaded model runtime with qualification-owned prompt metadata
   */
  public static ModelJarRuntime openRuntime(ModelJar model, ModelLoadOptions options) {
    requireVectorModule(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
    return classpathLoader().loadRuntime(model, options);
  }

  /**
   * Opens an exact ModelJars marker coordinate using automatic loading controls.
   *
   * @param markerCoordinate complete marker coordinate
   * @return ready-to-use text generation model
   */
  public static TextGenerationModel open(String markerCoordinate) {
    return open(ModelJar.of(markerCoordinate));
  }

  /**
   * Opens an exact marker coordinate with its qualified chat template and evidence.
   *
   * @param markerCoordinate complete marker coordinate
   * @return loaded model runtime with qualification-owned prompt metadata
   */
  public static ModelJarRuntime openRuntime(String markerCoordinate) {
    return openRuntime(ModelJar.of(markerCoordinate));
  }

  /**
   * Opens a qualified embedding model using its marker-owned pooling and normalization policy.
   *
   * @param model immutable model selector or generated catalog reference
   * @return ready-to-use embedding backend
   */
  public static EmbeddingBackend openEmbedding(ModelJar model) {
    return openEmbeddingRuntime(model).model();
  }

  /**
   * Opens a qualified embedding model with explicit cache and network controls.
   *
   * @param model immutable model selector or generated catalog reference
   * @param options backend, cache, and network controls
   * @return ready-to-use embedding backend
   */
  public static EmbeddingBackend openEmbedding(ModelJar model, ModelLoadOptions options) {
    return openEmbeddingRuntime(model, options).model();
  }

  /**
   * Opens a qualified embedding model together with its exact descriptor and equivalence evidence.
   *
   * @param model immutable model selector or generated catalog reference
   * @return loaded embedding runtime
   */
  public static ModelJarEmbeddingRuntime openEmbeddingRuntime(ModelJar model) {
    return openEmbeddingRuntime(model, ModelLoadOptions.defaults());
  }

  /**
   * Opens a qualified embedding model together with its exact descriptor and equivalence evidence.
   *
   * @param model immutable model selector or generated catalog reference
   * @param options backend, cache, and network controls
   * @return loaded embedding runtime
   */
  public static ModelJarEmbeddingRuntime openEmbeddingRuntime(
      ModelJar model, ModelLoadOptions options) {
    requireVectorModule(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
    return classpathLoader().loadEmbeddingRuntime(model, options);
  }

  /**
   * Opens an exact qualified embedding marker coordinate using automatic loading controls.
   *
   * @param markerCoordinate complete marker coordinate
   * @return ready-to-use embedding backend
   */
  public static EmbeddingBackend openEmbedding(String markerCoordinate) {
    return openEmbedding(ModelJar.of(markerCoordinate));
  }

  /**
   * Opens an exact qualified embedding marker coordinate and exposes its evidence.
   *
   * @param markerCoordinate complete marker coordinate
   * @return loaded embedding runtime
   */
  public static ModelJarEmbeddingRuntime openEmbeddingRuntime(String markerCoordinate) {
    return openEmbeddingRuntime(ModelJar.of(markerCoordinate));
  }

  /**
   * Opens a qualified cross-encoder reranker.
   *
   * @param model marker coordinate to resolve
   * @return ready-to-use reranking model
   */
  public static RerankingModel openReranker(ModelJar model) {
    return openRerankingRuntime(model).model();
  }

  /**
   * Opens a qualified cross-encoder reranker with explicit cache and network controls.
   *
   * @param model marker coordinate to resolve
   * @param options cache, network, and backend controls
   * @return ready-to-use reranking model
   */
  public static RerankingModel openReranker(ModelJar model, ModelLoadOptions options) {
    return openRerankingRuntime(model, options).model();
  }

  /**
   * Opens a qualified cross-encoder reranker and exposes its exact evidence.
   *
   * @param model marker coordinate to resolve
   * @return loaded reranking runtime
   */
  public static ModelJarRerankingRuntime openRerankingRuntime(ModelJar model) {
    return openRerankingRuntime(model, ModelLoadOptions.defaults());
  }

  /**
   * Opens a qualified cross-encoder reranker and exposes its exact evidence.
   *
   * @param model marker coordinate to resolve
   * @param options cache, network, and backend controls
   * @return loaded reranking runtime
   */
  public static ModelJarRerankingRuntime openRerankingRuntime(
      ModelJar model, ModelLoadOptions options) {
    requireVectorModule(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
    return classpathLoader().loadRerankingRuntime(model, options);
  }

  /**
   * Opens an exact qualified reranker marker coordinate.
   *
   * @param markerCoordinate complete marker coordinate
   * @return ready-to-use reranking model
   */
  public static RerankingModel openReranker(String markerCoordinate) {
    return openReranker(ModelJar.of(markerCoordinate));
  }

  /**
   * Opens an exact qualified reranker marker coordinate and exposes its evidence.
   *
   * @param markerCoordinate complete marker coordinate
   * @return loaded reranking runtime
   */
  public static ModelJarRerankingRuntime openRerankingRuntime(String markerCoordinate) {
    return openRerankingRuntime(ModelJar.of(markerCoordinate));
  }

  TextGenerationModel load(ModelJar model, ModelLoadOptions options) {
    return loadRuntime(model, options).model();
  }

  ModelJarRuntime loadRuntime(ModelJar model, ModelLoadOptions options) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(options, "options");
    ModelJarDescriptor descriptor =
        models
            .resolve(model)
            .orElseThrow(() -> new ModelJarException("No qualified ModelJar matched " + model));
    ModelExecutionQualification qualification = selectQualification(descriptor, options.backend());
    String backend = qualification.backend();
    List<String> activeJvmArguments = List.copyOf(jvmArguments.get());
    requireNativeAccess(backend, activeJvmArguments);
    Path artifact = installer.install(descriptor, options);
    Map<String, String> runtime = Map.copyOf(runtimeEnvironment.get());
    BackendConfiguration configuration =
        configuration(descriptor, qualification, runtime, activeJvmArguments);
    InferenceBackend loadedBackend = backendLoader.load(backend, artifact, configuration);
    return new ModelJarRuntime(new InferencePipeline(loadedBackend), descriptor, qualification);
  }

  EmbeddingBackend loadEmbedding(ModelJar model, ModelLoadOptions options) {
    return loadEmbeddingRuntime(model, options).model();
  }

  ModelJarEmbeddingRuntime loadEmbeddingRuntime(ModelJar model, ModelLoadOptions options) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(options, "options");
    ModelJarDescriptor descriptor =
        models
            .resolve(model)
            .orElseThrow(() -> new ModelJarException("No qualified ModelJar matched " + model));
    ModelEmbeddingQualificationRegistry.Entry qualification =
        selectEmbeddingQualification(descriptor, options.backend());
    Path artifact = installer.install(descriptor, options);
    Map<String, String> environment = new LinkedHashMap<>(runtimeEnvironment.get());
    environment.put("modeljars-marker", descriptor.markerCoordinate().toString());
    environment.put("modeljars-artifact-sha256", qualification.artifactSha256());
    environment.put("modeljars-qualification-workload", "oracle-equivalence-v1");
    EmbeddingBackend backend =
        embeddingBackendLoader.load(
            artifact, qualification, new BackendConfiguration(environment, Map.of(), List.of()));
    return new ModelJarEmbeddingRuntime(backend, descriptor, qualification);
  }

  RerankingModel loadReranker(ModelJar model, ModelLoadOptions options) {
    return loadRerankingRuntime(model, options).model();
  }

  ModelJarRerankingRuntime loadRerankingRuntime(ModelJar model, ModelLoadOptions options) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(options, "options");
    ModelJarDescriptor descriptor =
        models
            .resolve(model)
            .orElseThrow(() -> new ModelJarException("No qualified ModelJar matched " + model));
    ModelRerankingQualificationRegistry.Entry qualification =
        selectRerankingQualification(descriptor, options.backend());
    Path artifact = installer.install(descriptor, options);
    RerankingModel reranker = rerankingModelLoader.load(artifact, qualification);
    return new ModelJarRerankingRuntime(reranker, descriptor, qualification);
  }

  static void requireVectorModule(boolean available) {
    if (!available) {
      throw new ModelJarException(
          "ModelJars local inference requires the JDK Vector module. Restart with Java 25 or newer and "
              + "--add-modules=jdk.incubator.vector");
    }
  }

  static void requireNativeAccess(String backend, List<String> activeJvmArguments) {
    if (!NATIVE_BACKEND.equals(backend)) {
      return;
    }
    boolean enabled =
        activeJvmArguments.stream()
            .filter(argument -> argument.startsWith("--enable-native-access="))
            .map(argument -> argument.substring("--enable-native-access=".length()))
            .flatMap(value -> java.util.Arrays.stream(value.split(",")))
            .anyMatch("ALL-UNNAMED"::equals);
    if (!enabled) {
      throw new ModelJarException(
          "The qualified rust-ffm backend requires native access. Restart with Java 25 or newer and "
              + "--enable-native-access=ALL-UNNAMED, or explicitly select ModelBackend.JAVA "
              + "when that backend is qualified");
    }
  }

  private ModelExecutionQualification selectQualification(
      ModelJarDescriptor descriptor, ModelBackend requestedBackend) {
    List<ModelExecutionQualification> candidates =
        Stream.<ModelExecutionQualification>concat(
                qualifications.qualificationsFor(descriptor).stream(),
                toolQualifications.qualificationsFor(descriptor).stream())
            .filter(ModelExecutionQualification::productionUsable)
            .toList();
    if (requestedBackend != ModelBackend.AUTO) {
      String backend = requestedBackend.backendId();
      return candidates.stream()
          .filter(candidate -> candidate.backend().equals(backend))
          .findFirst()
          .orElseThrow(
              () ->
                  new ModelJarException(
                      "ModelJar "
                          + descriptor.markerCoordinate()
                          + " has no qualified "
                          + backend
                          + " execution"));
    }
    return candidates.stream()
        .min(
            Comparator.comparingDouble(ModelExecutionQualification::p95EndToEndMillis)
                .thenComparing(ModelExecutionQualification::backend)
                .thenComparing(ModelExecutionQualification::workload))
        .orElseThrow(
            () ->
                new ModelJarException(
                    "ModelJar "
                        + descriptor.markerCoordinate()
                        + " has no production qualification"));
  }

  private ModelEmbeddingQualificationRegistry.Entry selectEmbeddingQualification(
      ModelJarDescriptor descriptor, ModelBackend requestedBackend) {
    String artifactSha256 =
        descriptor
            .sha256()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "Embedding ModelJar "
                            + descriptor.markerCoordinate()
                            + " has no pinned artifact digest"));
    return embeddingQualifications.qualified().stream()
        .filter(candidate -> candidate.modelId().equals(descriptor.alias()))
        .filter(candidate -> candidate.artifactSha256().equalsIgnoreCase(artifactSha256))
        .filter(
            candidate ->
                requestedBackend == ModelBackend.AUTO
                    || candidate.backend().equals(requestedBackend.backendId()))
        .findFirst()
        .orElseThrow(
            () ->
                new ModelJarException(
                    "ModelJar "
                        + descriptor.markerCoordinate()
                        + " has no qualified "
                        + (requestedBackend == ModelBackend.AUTO
                            ? "embedding execution"
                            : requestedBackend.backendId() + " embedding execution")));
  }

  private ModelRerankingQualificationRegistry.Entry selectRerankingQualification(
      ModelJarDescriptor descriptor, ModelBackend requestedBackend) {
    String artifactSha256 =
        descriptor
            .sha256()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "Reranking ModelJar "
                            + descriptor.markerCoordinate()
                            + " has no pinned artifact digest"));
    return rerankingQualifications.qualified().stream()
        .filter(candidate -> candidate.modelId().equals(descriptor.alias()))
        .filter(candidate -> candidate.artifactSha256().equalsIgnoreCase(artifactSha256))
        .filter(
            candidate ->
                requestedBackend == ModelBackend.AUTO
                    || candidate.backend().equals(requestedBackend.backendId()))
        .findFirst()
        .orElseThrow(
            () ->
                new ModelJarException(
                    "ModelJar "
                        + descriptor.markerCoordinate()
                        + " has no qualified "
                        + (requestedBackend == ModelBackend.AUTO
                            ? "reranking execution"
                            : requestedBackend.backendId() + " reranking execution")));
  }

  private BackendConfiguration configuration(
      ModelJarDescriptor descriptor,
      ModelExecutionQualification qualification,
      Map<String, String> runtime,
      List<String> activeJvmArguments) {
    List<ModelPerformanceProfile> safeProfiles =
        profiles.matching(descriptor, qualification.backend(), runtime).stream()
            .filter(ModelPerformanceProfile::safeForAutomaticSelection)
            .sorted(
                Comparator.comparingInt(
                        (ModelPerformanceProfile profile) -> profile.runtimeSelector().size())
                    .reversed()
                    .thenComparing(ModelPerformanceProfile::id))
            .toList();
    List<ModelPerformanceProfile> applicableProfiles =
        safeProfiles.stream()
            .filter(
                profile ->
                    profile
                        .javaLaunch()
                        .map(launch -> launch.missingArguments(activeJvmArguments).isEmpty())
                        .orElse(true))
            .toList();
    Map<String, String> environment = new LinkedHashMap<>(runtime);
    environment.put("modeljars-marker", descriptor.markerCoordinate().toString());
    environment.put("modeljars-artifact-sha256", qualification.artifactSha256());
    environment.put("modeljars-qualification-workload", qualification.workload());

    List<OptimizationDecision> decisions = new ArrayList<>();
    List<ModelPerformanceProfile> launchBlockedProfiles =
        safeProfiles.stream().filter(profile -> !applicableProfiles.contains(profile)).toList();
    if (!launchBlockedProfiles.isEmpty()) {
      decisions.add(launchBlockedDecision(launchBlockedProfiles, activeJvmArguments));
    }
    Map<String, String> recommendations = mergeRecommendations(applicableProfiles);
    decisions.add(
        profileSelectionDecision(safeProfiles, applicableProfiles, qualification.backend()));
    return new BackendConfiguration(environment, recommendations, decisions);
  }

  private static OptimizationDecision launchBlockedDecision(
      List<ModelPerformanceProfile> profiles, List<String> activeJvmArguments) {
    String missingArguments =
        String.join(
            " ",
            profiles.stream()
                .flatMap(
                    profile ->
                        profile
                            .javaLaunch()
                            .orElseThrow()
                            .missingArguments(activeJvmArguments)
                            .stream())
                .distinct()
                .toList());
    return new OptimizationDecision(
        "modeljars.performance-profile-launch",
        OptimizationStatus.DISABLED,
        "required JVM startup arguments are not active",
        Map.of("profiles", profileIds(profiles), "missing-jvm-arguments", missingArguments));
  }

  private static Map<String, String> mergeRecommendations(List<ModelPerformanceProfile> profiles) {
    Map<String, String> recommendations = new LinkedHashMap<>();
    for (ModelPerformanceProfile profile : profiles) {
      profile
          .recommendations()
          .forEach(
              (name, value) -> {
                String previous = recommendations.putIfAbsent(name, value);
                if (previous != null && !previous.equals(value)) {
                  throw new ModelJarException(
                      "Conflicting performance recommendations for "
                          + name
                          + ": "
                          + previous
                          + " and "
                          + value);
                }
              });
    }
    return Map.copyOf(recommendations);
  }

  private static OptimizationDecision profileSelectionDecision(
      List<ModelPerformanceProfile> safeProfiles,
      List<ModelPerformanceProfile> applicableProfiles,
      String backend) {
    if (applicableProfiles.isEmpty()) {
      return new OptimizationDecision(
          "modeljars.performance-profile",
          OptimizationStatus.DISABLED,
          safeProfiles.isEmpty()
              ? "no exact artifact, backend, and runtime profile matched"
              : "matching profiles require JVM startup arguments that are not active",
          Map.of("backend", backend));
    }
    return new OptimizationDecision(
        "modeljars.performance-profile",
        OptimizationStatus.ENABLED,
        "applied artifact-bound profiles with deterministic benchmark output",
        Map.of(
            "profiles",
            profileIds(applicableProfiles),
            "benchmarks",
            String.join(
                ",",
                applicableProfiles.stream()
                    .map(profile -> profile.evidence().benchmarkId())
                    .toList())));
  }

  private static String profileIds(List<ModelPerformanceProfile> profiles) {
    return String.join(",", profiles.stream().map(ModelPerformanceProfile::id).toList());
  }

  private static ModelJars classpathLoader() {
    ModelJarRegistry models = ModelJarRegistry.fromClasspath();
    ModelJarInstaller installer = new ModelJarInstaller(models);
    return new ModelJars(
        models,
        ModelRagQualificationRegistry.fromClasspath(),
        ModelPerformanceProfileRegistry.fromClasspath(),
        (descriptor, options) -> {
          Path artifact = cachePath(descriptor, options.cacheDirectory());
          return options.offline()
              ? installer.verifyCached(descriptor, artifact)
              : installer.install(descriptor, artifact);
        },
        ModelJars::loadBackend,
        () -> RuntimeFingerprint.capture().asEnvironment());
  }

  private static Path cachePath(ModelJarDescriptor descriptor, Path cacheDirectory) {
    boolean supported =
        descriptor.format().equals("gguf")
            || descriptor.format().equals("cact")
            || (descriptor.format().equals("safetensors") && !descriptor.files().isEmpty());
    if (!supported) {
      throw new ModelJarException(
          "Models backends cannot load ModelJar format " + descriptor.format());
    }
    return ModelJarCache.artifactPath(descriptor, cacheDirectory);
  }

  static InferenceBackend loadBackend(
      String backend, Path artifact, BackendConfiguration configuration) {
    return switch (backend) {
      case JAVA_BACKEND -> PureJavaBackend.loadAutomatic(artifact, configuration);
      case NATIVE_BACKEND -> RustFfmBackend.load(artifact, configuration);
      default -> throw new ModelJarException("Unsupported Models backend: " + backend);
    };
  }

  private static EmbeddingBackend loadEmbeddingBackend(
      Path artifact,
      ModelEmbeddingQualificationRegistry.Entry qualification,
      BackendConfiguration configuration) {
    if (!JAVA_BACKEND.equals(qualification.backend())) {
      throw new ModelJarException(
          "Unsupported Models embedding backend: " + qualification.backend());
    }
    Pooling pooling =
        switch (qualification.pooling()) {
          case "last-token" -> Pooling.LAST_TOKEN;
          case "mean" -> Pooling.MEAN;
          default ->
              throw new ModelJarException(
                  "Unsupported qualified embedding pooling: " + qualification.pooling());
        };
    PureJavaBackend backend = PureJavaBackend.loadAutomatic(artifact, configuration);
    try {
      var builder = GgufEmbeddingBackend.builder(backend).normalize(qualification.normalized());
      if (!backend.supportsSequenceEmbedding()) {
        builder.pooling(pooling);
      }
      EmbeddingBackend embedding = builder.build();
      int loadedDimension = embedding.dimension();
      if (loadedDimension != qualification.embeddingDimension()) {
        throw new ModelJarException(
            "Qualified embedding dimension "
                + qualification.embeddingDimension()
                + " does not match loaded model dimension "
                + loadedDimension);
      }
      return embedding;
    } catch (RuntimeException | Error failure) {
      backend.close();
      throw failure;
    }
  }

  private static RerankingModel loadRerankingModel(
      Path artifact, ModelRerankingQualificationRegistry.Entry qualification) {
    if (!JAVA_BACKEND.equals(qualification.backend())) {
      throw new ModelJarException(
          "Unsupported Models reranking backend: " + qualification.backend());
    }
    return GgufRerankingModel.load(artifact);
  }

  @FunctionalInterface
  interface ArtifactInstaller {
    Path install(ModelJarDescriptor descriptor, ModelLoadOptions options);
  }

  @FunctionalInterface
  interface BackendLoader {
    InferenceBackend load(String backend, Path artifact, BackendConfiguration configuration);
  }

  @FunctionalInterface
  interface EmbeddingBackendLoader {
    EmbeddingBackend load(
        Path artifact,
        ModelEmbeddingQualificationRegistry.Entry qualification,
        BackendConfiguration configuration);
  }

  @FunctionalInterface
  interface RerankingModelLoader {
    RerankingModel load(Path artifact, ModelRerankingQualificationRegistry.Entry qualification);
  }
}
