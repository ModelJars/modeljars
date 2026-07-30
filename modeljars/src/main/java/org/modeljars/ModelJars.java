package org.modeljars;

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.OptimizationDecision;
import com.integrallis.models.api.OptimizationStatus;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.api.TokenStream;
import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.plan.RuntimeFingerprint;
import com.integrallis.models.runtime.RuntimeTextGenerationModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Resolves, verifies, configures, and opens qualified models from ModelJars metadata.
 *
 * <p>The returned {@link TextGenerationModel} owns its inference backend and must be closed.
 */
public final class ModelJars {
  private static final String JAVA_BACKEND = "pure-java";
  private static final String NATIVE_BACKEND = "rust-ffm";

  private final ModelJarRegistry models;
  private final ModelRagQualificationRegistry qualifications;
  private final ModelPerformanceProfileRegistry profiles;
  private final ArtifactInstaller installer;
  private final BackendLoader backendLoader;
  private final Supplier<Map<String, String>> runtimeEnvironment;

  ModelJars(
      ModelJarRegistry models,
      ModelRagQualificationRegistry qualifications,
      ModelPerformanceProfileRegistry profiles,
      ArtifactInstaller installer,
      BackendLoader backendLoader,
      Supplier<Map<String, String>> runtimeEnvironment) {
    this.models = Objects.requireNonNull(models, "models");
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.installer = Objects.requireNonNull(installer, "installer");
    this.backendLoader = Objects.requireNonNull(backendLoader, "backendLoader");
    this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment");
  }

  /**
   * Opens a qualified model using automatic backend selection and the default cache.
   *
   * @param model immutable model selector or generated catalog reference
   * @return ready-to-use text generation model
   */
  public static TextGenerationModel open(ModelJar model) {
    return open(model, ModelLoadOptions.defaults());
  }

  /**
   * Opens a qualified model with explicit loading controls.
   *
   * @param model immutable model selector or generated catalog reference
   * @param options backend, cache, and network controls
   * @return ready-to-use text generation model
   */
  public static TextGenerationModel open(ModelJar model, ModelLoadOptions options) {
    return classpathLoader().load(model, options);
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

  TextGenerationModel load(ModelJar model, ModelLoadOptions options) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(options, "options");
    ModelJarDescriptor descriptor =
        models
            .resolve(model)
            .orElseThrow(() -> new ModelJarException("No qualified ModelJar matched " + model));
    ModelRagQualification qualification = selectQualification(descriptor, options.backend());
    String backend = qualification.backend();
    Path artifact = installer.install(descriptor, options);
    Map<String, String> runtime = Map.copyOf(runtimeEnvironment.get());
    BackendConfiguration configuration = configuration(descriptor, qualification, runtime);
    InferenceBackend loadedBackend = backendLoader.load(backend, artifact, configuration);
    return new ManagedTextGenerationModel(loadedBackend);
  }

  private ModelRagQualification selectQualification(
      ModelJarDescriptor descriptor, ModelBackend requestedBackend) {
    List<ModelRagQualification> candidates =
        qualifications.qualificationsFor(descriptor).stream()
            .filter(ModelRagQualification::productionUsable)
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
            Comparator.comparingDouble(ModelRagQualification::p95EndToEndMillis)
                .thenComparing(
                    Comparator.comparingDouble(
                            ModelRagQualification::p50DecodeTokensPerSecond)
                        .reversed())
                .thenComparing(ModelRagQualification::backend))
        .orElseThrow(
            () ->
                new ModelJarException(
                    "ModelJar "
                        + descriptor.markerCoordinate()
                        + " has no production qualification"));
  }

  private BackendConfiguration configuration(
      ModelJarDescriptor descriptor,
      ModelRagQualification qualification,
      Map<String, String> runtime) {
    List<ModelPerformanceProfile> matchingProfiles =
        profiles.matching(descriptor, qualification.backend(), runtime).stream()
            .filter(ModelPerformanceProfile::safeForAutomaticSelection)
            .sorted(
                Comparator.comparingInt(
                        (ModelPerformanceProfile profile) ->
                            profile.runtimeSelector().size())
                    .reversed()
                    .thenComparing(ModelPerformanceProfile::id))
            .toList();
    ModelPerformanceProfile profile =
        matchingProfiles.isEmpty() ? null : matchingProfiles.getFirst();

    Map<String, String> environment = new LinkedHashMap<>(runtime);
    environment.put("modeljars-marker", descriptor.markerCoordinate().toString());
    environment.put("modeljars-artifact-sha256", qualification.artifactSha256());
    environment.put("modeljars-qualification-workload", qualification.workload());

    List<OptimizationDecision> decisions = new ArrayList<>();
    Map<String, String> recommendations = Map.of();
    if (profile == null) {
      decisions.add(
          new OptimizationDecision(
              "modeljars.performance-profile",
              OptimizationStatus.DISABLED,
              "no exact artifact, backend, and runtime profile matched",
              Map.of("backend", qualification.backend())));
    } else {
      recommendations = profile.recommendations();
      decisions.add(
          new OptimizationDecision(
              "modeljars.performance-profile",
              OptimizationStatus.ENABLED,
              "applied an artifact-bound profile with deterministic benchmark output",
              Map.of(
                  "profile", profile.id(),
                  "benchmark", profile.evidence().benchmarkId())));
    }
    return new BackendConfiguration(environment, recommendations, decisions);
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
    String sha256 =
        descriptor
            .sha256()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "ModelJar has no artifact SHA-256: " + descriptor.markerCoordinate()));
    if (!descriptor.format().equals("gguf")) {
      throw new ModelJarException(
          "Models backends cannot load ModelJar format " + descriptor.format());
    }
    return cacheDirectory
        .resolve("sha256")
        .resolve(sha256.substring(0, 2))
        .resolve(sha256)
        .resolve("model.gguf");
  }

  private static InferenceBackend loadBackend(
      String backend, Path artifact, BackendConfiguration configuration) {
    return switch (backend) {
      case JAVA_BACKEND -> PureJavaBackend.load(artifact, configuration);
      case NATIVE_BACKEND -> RustFfmBackend.load(artifact, configuration);
      default -> throw new ModelJarException("Unsupported Models backend: " + backend);
    };
  }

  @FunctionalInterface
  interface ArtifactInstaller {
    Path install(ModelJarDescriptor descriptor, ModelLoadOptions options);
  }

  @FunctionalInterface
  interface BackendLoader {
    InferenceBackend load(String backend, Path artifact, BackendConfiguration configuration);
  }

  private static final class ManagedTextGenerationModel implements TextGenerationModel {
    private final InferenceBackend backend;
    private final RuntimeTextGenerationModel delegate;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ManagedTextGenerationModel(InferenceBackend backend) {
      this.backend = Objects.requireNonNull(backend, "backend");
      delegate = new RuntimeTextGenerationModel(backend);
    }

    @Override
    public String modelName() {
      return delegate.modelName();
    }

    @Override
    public BackendDiagnostics diagnostics() {
      return delegate.diagnostics();
    }

    @Override
    public String generate(String prompt, SamplingOptions options) {
      return delegate.generate(prompt, options);
    }

    @Override
    public void generate(String prompt, SamplingOptions options, TokenStream stream) {
      delegate.generate(prompt, options, stream);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        backend.close();
      }
    }
  }
}
