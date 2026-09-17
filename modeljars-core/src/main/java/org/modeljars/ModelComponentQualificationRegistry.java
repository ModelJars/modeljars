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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Loads immutable qualification bindings for internal hybrid components. */
public final class ModelComponentQualificationRegistry {
  /** Classpath location of the version-one component qualification resource. */
  public static final String RESOURCE = "META-INF/modeljars/component-qualifications-v1.properties";

  /** Supported component qualification resource schema version. */
  public static final int SCHEMA_VERSION = 1;

  private static final String ROOT_PREFIX = "modeljars.componentQualifications.";
  private static final String ENTRY_PREFIX = "componentQualification.";
  private static final String POLICY = "activated-adapter-component-v1";

  /** The component shape of a tool-calling adapter we trained ourselves. */
  public static final String TRAINED_TOOL_SPECIALIST = "trained-tool-specialist";

  /** The component shape of a publisher-trained adapter that Models runs unchanged. */
  public static final String UPSTREAM_RAG_SPECIALIST = "upstream-rag-specialist";

  /**
   * The component shape of a RAG adapter we trained ourselves, bound to its training commit and
   * required to strictly beat its base.
   */
  public static final String FIRST_PARTY_RAG_SPECIALIST = "first-party-rag-specialist";

  private static final Set<String> SPECIALIST_KINDS =
      Set.of(TRAINED_TOOL_SPECIALIST, UPSTREAM_RAG_SPECIALIST, FIRST_PARTY_RAG_SPECIALIST);

  /** Backend identifier of the Java Vector API kernels. */
  public static final String JAVA_BACKEND = "pure-java";

  /** Backend identifier of the Models-owned Rust FFM kernels. */
  public static final String NATIVE_BACKEND = "rust-ffm";

  private static final Set<String> BACKENDS = Set.of(JAVA_BACKEND, NATIVE_BACKEND);

  private final Instant generatedAt;
  private final String policyVersion;
  private final String modelsRevision;
  private final String evidenceRevision;
  private final List<Entry> entries;

  private ModelComponentQualificationRegistry(
      Instant generatedAt,
      String policyVersion,
      String modelsRevision,
      String evidenceRevision,
      List<Entry> entries) {
    this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    this.policyVersion = requireText(policyVersion, "policyVersion");
    if (!POLICY.equals(this.policyVersion)) {
      throw new ModelJarException("Unsupported component qualification policy: " + policyVersion);
    }
    this.modelsRevision = requireRevision(modelsRevision, "modelsRevision");
    this.evidenceRevision = requireRevision(evidenceRevision, "evidenceRevision");
    this.entries = entries.stream().sorted(Comparator.comparing(Entry::modelId)).toList();
    if (this.entries.stream().map(Entry::modelId).distinct().count() != this.entries.size()) {
      throw new ModelJarException("Component qualification model IDs must be unique");
    }
  }

  /**
   * Returns when this qualification collection was generated.
   *
   * @return time at which this evidence collection was generated
   */
  public Instant generatedAt() {
    return generatedAt;
  }

  /**
   * Returns the qualification policy version.
   *
   * @return qualification policy version applied to the evidence
   */
  public String policyVersion() {
    return policyVersion;
  }

  /**
   * Returns the exact Models implementation revision exercised by the evidence.
   *
   * @return immutable Models repository revision the evidence exercised
   */
  public String modelsRevision() {
    return modelsRevision;
  }

  /**
   * Returns the immutable Models revision containing the evidence report.
   *
   * @return immutable Models repository revision that carries the evidence report
   */
  public String evidenceRevision() {
    return evidenceRevision;
  }

  /**
   * Returns every component qualification in stable model-ID order.
   *
   * @return all component qualification entries ordered by model identifier
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Returns the number of qualified components.
   *
   * @return number of components satisfying the qualification policy
   */
  public int qualifiedModels() {
    return Math.toIntExact(entries.stream().filter(Entry::qualified).count());
  }

  /**
   * Returns the number of rejected components.
   *
   * @return number of components rejected by the qualification policy
   */
  public int rejectedModels() {
    return entries.size() - qualifiedModels();
  }

  /**
   * Finds evidence that binds an activated component to both exact verified artifacts.
   *
   * @param component activated component descriptor
   * @param base exact base-model descriptor
   * @return matching production qualification, if present
   */
  public Optional<Entry> qualificationFor(ModelJarDescriptor component, ModelJarDescriptor base) {
    return entries.stream()
        .filter(Entry::qualified)
        .filter(entry -> entry.matches(component, base))
        .findFirst();
  }

  /**
   * Loads a versioned component qualification resource from a file.
   *
   * @param path component qualification properties file
   * @return parsed and validated component qualifications
   */
  public static ModelComponentQualificationRegistry load(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
      return parse(input);
    } catch (IOException failure) {
      throw new ModelJarException(
          "Unable to load ModelJars component qualifications: " + path, failure);
    }
  }

  /**
   * Loads and merges component qualifications visible to the context class loader.
   *
   * @return merged component qualifications from every visible resource
   */
  public static ModelComponentQualificationRegistry fromClasspath() {
    return fromClasspath(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads and merges component qualifications visible to a class loader.
   *
   * @param classLoader class loader whose resources are merged
   * @return merged component qualifications from every visible resource
   */
  public static ModelComponentQualificationRegistry fromClasspath(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null
            ? ModelComponentQualificationRegistry.class.getClassLoader()
            : classLoader;
    Map<String, SourcedEntry> merged = new LinkedHashMap<>();
    ModelComponentQualificationRegistry metadata = null;
    try {
      Enumeration<URL> resources = loader.getResources(RESOURCE);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        ModelComponentQualificationRegistry registry;
        try (InputStream input = resource.openStream()) {
          registry = parse(input);
        }
        if (metadata == null || registry.generatedAt.isAfter(metadata.generatedAt)) {
          metadata = registry;
        } else if (registry.generatedAt.equals(metadata.generatedAt)) {
          requireCompatibleMetadata(metadata, registry);
        }
        for (Entry entry : registry.entries) {
          merged.merge(
              entry.modelId(),
              new SourcedEntry(registry.generatedAt, entry),
              ModelComponentQualificationRegistry::newestEntry);
        }
      }
    } catch (IOException failure) {
      throw new ModelJarException("Unable to load component qualification resources", failure);
    }
    if (metadata == null) {
      throw new ModelJarException("No ModelJars component qualification resources found");
    }
    return new ModelComponentQualificationRegistry(
        metadata.generatedAt,
        metadata.policyVersion,
        metadata.modelsRevision,
        metadata.evidenceRevision,
        merged.values().stream().map(SourcedEntry::entry).toList());
  }

  /**
   * Parses one component qualification properties stream.
   *
   * @param stream component qualification properties
   * @return parsed and validated component qualifications
   * @throws IOException if the stream cannot be read
   */
  public static ModelComponentQualificationRegistry parse(InputStream stream) throws IOException {
    Properties properties = new Properties();
    properties.load(Objects.requireNonNull(stream, "stream"));
    return fromProperties(properties);
  }

  /**
   * Parses and validates component qualification properties.
   *
   * @param properties component qualification properties
   * @return parsed and validated component qualifications
   */
  public static ModelComponentQualificationRegistry fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    int schemaVersion = integer(properties, ROOT_PREFIX + "schemaVersion");
    if (schemaVersion != SCHEMA_VERSION) {
      throw new ModelJarException(
          "Unsupported component qualification schema version: " + schemaVersion);
    }
    ModelComponentQualificationRegistry registry =
        new ModelComponentQualificationRegistry(
            instant(properties, ROOT_PREFIX + "generatedAt"),
            required(properties, ROOT_PREFIX + "policyVersion"),
            required(properties, ROOT_PREFIX + "modelsRevision"),
            required(properties, ROOT_PREFIX + "evidenceRevision"),
            entryIds(properties).stream().map(id -> entry(id, properties)).toList());
    if (integer(properties, ROOT_PREFIX + "qualifiedModels") != registry.qualifiedModels()
        || integer(properties, ROOT_PREFIX + "rejectedModels") != registry.rejectedModels()) {
      throw new ModelJarException("Component qualification counts do not match resource entries");
    }
    return registry;
  }

  private static Set<String> entryIds(Properties properties) {
    Set<String> ids = new TreeSet<>();
    String entryMarker = ".artifactFile.count";
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith(ENTRY_PREFIX) && name.endsWith(entryMarker)) {
        String id = name.substring(ENTRY_PREFIX.length(), name.length() - entryMarker.length());
        if (!id.isBlank()) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  private static Entry entry(String id, Properties properties) {
    String prefix = ENTRY_PREFIX + id + ".";
    int fileCount = integer(properties, prefix + "artifactFile.count");
    if (fileCount < 2) {
      throw new ModelJarException("Component qualification must bind at least two artifact files");
    }
    List<ArtifactFile> files = new ArrayList<>(fileCount);
    for (int index = 0; index < fileCount; index++) {
      String filePrefix = prefix + "artifactFile." + "%03d".formatted(index) + ".";
      files.add(
          new ArtifactFile(
              required(properties, filePrefix + "path"),
              required(properties, filePrefix + "role"),
              required(properties, filePrefix + "sha256"),
              longValue(properties, filePrefix + "sizeBytes")));
    }
    return new Entry(
        id,
        required(properties, prefix + "baseModelId"),
        required(properties, prefix + "baseArtifactSha256"),
        longValue(properties, prefix + "baseArtifactSizeBytes"),
        required(properties, prefix + "artifactSha256"),
        longValue(properties, prefix + "artifactSizeBytes"),
        files,
        longValue(properties, prefix + "artifactBundleSizeBytes"),
        required(properties, prefix + "artifactBundleSha256"),
        integer(properties, prefix + "minimumSharedPrefixTokens"),
        URI.create(required(properties, prefix + "reportUri")),
        required(properties, prefix + "reportSha256"),
        bool(properties, prefix + "qualified"),
        properties.getProperty(prefix + "specialistKind", TRAINED_TOOL_SPECIALIST),
        properties.getProperty(prefix + "backend", JAVA_BACKEND));
  }

  private static SourcedEntry newestEntry(SourcedEntry first, SourcedEntry other) {
    if (first.entry.equals(other.entry)) {
      return first.generatedAt.isBefore(other.generatedAt) ? other : first;
    }
    int recency = first.generatedAt.compareTo(other.generatedAt);
    if (recency < 0) {
      return other;
    }
    if (recency > 0) {
      return first;
    }
    throw new ModelJarException(
        "Conflicting component qualification at the same generation instant: "
            + first.entry.modelId());
  }

  private static void requireCompatibleMetadata(
      ModelComponentQualificationRegistry first, ModelComponentQualificationRegistry other) {
    if (!first.policyVersion.equals(other.policyVersion)
        || !first.modelsRevision.equals(other.modelsRevision)
        || !first.evidenceRevision.equals(other.evidenceRevision)) {
      throw new ModelJarException(
          "Conflicting component qualification metadata at the same generation instant");
    }
  }

  private record SourcedEntry(Instant generatedAt, Entry entry) {}

  /**
   * Immutable file identity bound by component evidence.
   *
   * @param path artifact-relative file path
   * @param role role the file plays in the runtime bundle
   * @param sha256 lowercase SHA-256 of the file
   * @param sizeBytes file size in bytes
   */
  public record ArtifactFile(String path, String role, String sha256, long sizeBytes) {
    /** Validates one immutable artifact-file identity. */
    public ArtifactFile {
      path = requireText(path, "path");
      role = requireText(role, "role");
      sha256 = requireSha256(sha256, "artifact file SHA-256");
      if (sizeBytes <= 0) {
        throw new IllegalArgumentException("artifact file sizeBytes must be positive");
      }
    }
  }

  /**
   * Immutable evidence binding for one internal hybrid component.
   *
   * @param modelId catalog identifier of the component
   * @param baseModelId catalog identifier of the physical base model
   * @param baseArtifactSha256 lowercase SHA-256 of the base artifact
   * @param baseArtifactSizeBytes base artifact size in bytes
   * @param artifactSha256 lowercase SHA-256 of the component's primary artifact
   * @param artifactSizeBytes primary artifact size in bytes
   * @param artifactFiles every file of the runtime bundle
   * @param artifactBundleSizeBytes total bundle size in bytes
   * @param artifactBundleSha256 lowercase SHA-256 over the ordered bundle identities
   * @param minimumSharedPrefixTokens smallest prefix at which physical sharing pays off
   * @param reportUri immutable evidence report location
   * @param reportSha256 lowercase SHA-256 of the evidence report
   * @param qualified whether the component satisfies the policy
   * @param specialistKind kind of specialist the evidence was gated as
   * @param backend execution backend the component evidence was measured on
   */
  public record Entry(
      String modelId,
      String baseModelId,
      String baseArtifactSha256,
      long baseArtifactSizeBytes,
      String artifactSha256,
      long artifactSizeBytes,
      List<ArtifactFile> artifactFiles,
      long artifactBundleSizeBytes,
      String artifactBundleSha256,
      int minimumSharedPrefixTokens,
      URI reportUri,
      String reportSha256,
      boolean qualified,
      String specialistKind,
      String backend) {
    /** Validates a complete component evidence binding. */
    public Entry {
      modelId = requireText(modelId, "modelId");
      baseModelId = requireText(baseModelId, "baseModelId");
      baseArtifactSha256 = requireSha256(baseArtifactSha256, "baseArtifactSha256");
      artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
      if (baseArtifactSizeBytes <= 0 || artifactSizeBytes <= 0 || artifactBundleSizeBytes <= 0) {
        throw new IllegalArgumentException("component artifact sizes must be positive");
      }
      artifactFiles = List.copyOf(Objects.requireNonNull(artifactFiles, "artifactFiles"));
      if (artifactFiles.size() < 2
          || artifactFiles.stream().map(ArtifactFile::path).distinct().count()
              != artifactFiles.size()) {
        throw new IllegalArgumentException(
            "component qualification must bind distinct artifact files");
      }
      String primaryArtifactSha256 = artifactSha256;
      long primaryArtifactSizeBytes = artifactSizeBytes;
      if (artifactFiles.stream()
          .noneMatch(
              file ->
                  file.sha256().equals(primaryArtifactSha256)
                      && file.sizeBytes() == primaryArtifactSizeBytes)) {
        throw new IllegalArgumentException(
            "one component artifact file must match artifactSha256 and artifactSizeBytes");
      }
      long measuredSize = artifactFiles.stream().mapToLong(ArtifactFile::sizeBytes).sum();
      if (measuredSize != artifactBundleSizeBytes) {
        throw new IllegalArgumentException("artifactBundleSizeBytes does not match artifact files");
      }
      artifactBundleSha256 = requireSha256(artifactBundleSha256, "artifactBundleSha256");
      if (!artifactBundleSha256.equals(bundleSha256(artifactFiles))) {
        throw new IllegalArgumentException("artifactBundleSha256 does not match artifact files");
      }
      if (minimumSharedPrefixTokens <= 0) {
        throw new IllegalArgumentException("minimumSharedPrefixTokens must be positive");
      }
      reportUri = Objects.requireNonNull(reportUri, "reportUri");
      reportSha256 = requireSha256(reportSha256, "reportSha256");
      specialistKind = requireText(specialistKind, "specialistKind");
      if (!SPECIALIST_KINDS.contains(specialistKind)) {
        throw new IllegalArgumentException("unsupported specialistKind: " + specialistKind);
      }
      backend = requireText(backend, "backend");
      if (!BACKENDS.contains(backend)) {
        throw new IllegalArgumentException(
            "unsupported component qualification backend: " + backend);
      }
    }

    private boolean matches(ModelJarDescriptor component, ModelJarDescriptor base) {
      List<ArtifactFile> descriptorFiles =
          component.files().stream()
              .map(
                  file ->
                      new ArtifactFile(file.path(), file.role(), file.sha256(), file.sizeBytes()))
              .sorted(Comparator.comparing(ArtifactFile::path))
              .toList();
      return component.alias().equals(modelId)
          && base.alias().equals(baseModelId)
          && base.sha256().filter(baseArtifactSha256::equals).isPresent()
          && base.sizeBytes().filter(size -> size == baseArtifactSizeBytes).isPresent()
          && component.sha256().filter(artifactSha256::equals).isPresent()
          && component.sizeBytes().filter(size -> size == artifactSizeBytes).isPresent()
          && descriptorFiles.equals(
              artifactFiles.stream().sorted(Comparator.comparing(ArtifactFile::path)).toList())
          && artifactBundleSizeBytes
              == descriptorFiles.stream().mapToLong(ArtifactFile::sizeBytes).sum()
          && artifactBundleSha256.equals(bundleSha256(descriptorFiles));
    }
  }

  private static String bundleSha256(List<ArtifactFile> files) {
    StringBuilder identity = new StringBuilder();
    files.stream()
        .sorted(Comparator.comparing(ArtifactFile::path))
        .forEach(
            file ->
                identity
                    .append(file.path())
                    .append('\t')
                    .append(file.sizeBytes())
                    .append('\t')
                    .append(file.sha256())
                    .append('\n'));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(identity.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new ModelJarException("Missing required property: " + name);
    }
    return value.trim();
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static String requireRevision(String value, String name) {
    String revision = requireText(value, name);
    if (!revision.matches("[a-f0-9]{40}")) {
      throw new IllegalArgumentException(
          name + " must contain 40 lowercase hexadecimal characters");
    }
    return revision;
  }

  private static String requireSha256(String value, String name) {
    String digest = requireText(value, name).toLowerCase(java.util.Locale.ROOT);
    if (!digest.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
    }
    return digest;
  }

  private static int integer(Properties properties, String name) {
    try {
      return Integer.parseInt(required(properties, name));
    } catch (NumberFormatException failure) {
      throw new ModelJarException("Invalid integer property: " + name, failure);
    }
  }

  private static long longValue(Properties properties, String name) {
    try {
      return Long.parseLong(required(properties, name));
    } catch (NumberFormatException failure) {
      throw new ModelJarException("Invalid long property: " + name, failure);
    }
  }

  private static boolean bool(Properties properties, String name) {
    String value = required(properties, name);
    if (value.equalsIgnoreCase("true")) {
      return true;
    }
    if (value.equalsIgnoreCase("false")) {
      return false;
    }
    throw new ModelJarException("Invalid boolean property: " + name);
  }

  private static Instant instant(Properties properties, String name) {
    try {
      return Instant.parse(required(properties, name));
    } catch (DateTimeParseException failure) {
      throw new ModelJarException("Invalid instant property: " + name, failure);
    }
  }
}
