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
import java.lang.management.ManagementFactory;
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
 * <p>Returned models and runtimes own their inference backend and must be closed.
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
        profiles,
        installer,
        backendLoader,
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
    this.models = Objects.requireNonNull(models, "models");
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.installer = Objects.requireNonNull(installer, "installer");
    this.backendLoader = Objects.requireNonNull(backendLoader, "backendLoader");
    this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment");
    this.jvmArguments = Objects.requireNonNull(jvmArguments, "jvmArguments");
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
   * Opens a qualified model with explicit loading controls and qualification-owned prompt
   * metadata.
   *
   * @param model immutable model selector or generated catalog reference
   * @param options backend, cache, and network controls
   * @return loaded model runtime with qualification-owned prompt metadata
   */
  public static ModelJarRuntime openRuntime(ModelJar model, ModelLoadOptions options) {
    requireVectorModule(
        ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
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
    ModelRagQualification qualification = selectQualification(descriptor, options.backend());
    String backend = qualification.backend();
    List<String> activeJvmArguments = List.copyOf(jvmArguments.get());
    requireNativeAccess(backend, activeJvmArguments);
    Path artifact = installer.install(descriptor, options);
    Map<String, String> runtime = Map.copyOf(runtimeEnvironment.get());
    BackendConfiguration configuration =
        configuration(descriptor, qualification, runtime, activeJvmArguments);
    InferenceBackend loadedBackend = backendLoader.load(backend, artifact, configuration);
    return new ModelJarRuntime(
        new ManagedTextGenerationModel(loadedBackend), descriptor, qualification);
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
      Map<String, String> runtime,
      List<String> activeJvmArguments) {
    List<ModelPerformanceProfile> safeProfiles =
        profiles.matching(descriptor, qualification.backend(), runtime).stream()
            .filter(ModelPerformanceProfile::safeForAutomaticSelection)
            .sorted(
                Comparator.comparingInt(
                        (ModelPerformanceProfile profile) ->
                            profile.runtimeSelector().size())
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
        profileSelectionDecision(
            safeProfiles, applicableProfiles, qualification.backend()));
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
        Map.of(
            "profiles", profileIds(profiles),
            "missing-jvm-arguments", missingArguments));
  }

  private static Map<String, String> mergeRecommendations(
      List<ModelPerformanceProfile> profiles) {
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
    if (!descriptor.format().equals("gguf")) {
      throw new ModelJarException(
          "Models backends cannot load ModelJar format " + descriptor.format());
    }
    return ModelJarCache.artifactPath(descriptor, cacheDirectory);
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
