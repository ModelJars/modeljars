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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Loads model descriptors from the ModelJars properties marker format. */
public final class PropertiesModelJarRegistry extends InMemoryModelJarRegistry {
  private static final String MODEL_PREFIX = "model.";

  private PropertiesModelJarRegistry(List<ModelJarDescriptor> descriptors) {
    super(descriptors);
  }

  /**
   * Loads model descriptors from a marker properties file.
   *
   * @param path marker properties file
   * @return parsed registry
   */
  public static PropertiesModelJarRegistry load(Path path) {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars registry: " + path, e);
    }
    return fromProperties(properties);
  }

  /**
   * Parses model descriptors from marker properties.
   *
   * @param properties marker properties
   * @return parsed registry
   */
  public static PropertiesModelJarRegistry fromProperties(Properties properties) {
    Set<String> aliases = aliases(properties);
    List<ModelJarDescriptor> descriptors =
        aliases.stream().sorted().map(alias -> descriptor(alias, properties)).toList();
    return new PropertiesModelJarRegistry(descriptors);
  }

  private static Set<String> aliases(Properties properties) {
    Set<String> aliases = new HashSet<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith(MODEL_PREFIX)) {
        continue;
      }
      String remaining = name.substring(MODEL_PREFIX.length());
      int separator = remaining.indexOf('.');
      if (separator > 0) {
        aliases.add(remaining.substring(0, separator));
      }
    }
    return aliases;
  }

  private static ModelJarDescriptor descriptor(String alias, Properties properties) {
    String prefix = MODEL_PREFIX + alias + ".";
    Map<String, Boolean> backends = backends(prefix, properties);
    Optional<URI> downloadUri = optional(prefix, "downloadUri", properties).map(URI::create);
    Optional<String> sha256 = optional(prefix, "sha256", properties);
    Optional<Long> sizeBytes =
        optional(prefix, "sizeBytes", properties).map(PropertiesModelJarRegistry::parseSizeBytes);
    return new ModelJarDescriptor(
        alias,
        required(prefix, "sourceId", properties),
        ModelJarCoordinate.parse(required(prefix, "markerCoordinate", properties)),
        ModelVersion.parse(required(prefix, "modelVersion", properties)),
        required(prefix, "variant", properties),
        required(prefix, "format", properties),
        required(prefix, "architecture", properties),
        required(prefix, "quantization", properties),
        optional(prefix, "path", properties).map(PropertiesModelJarRegistry::expandPath),
        optional(prefix, "classpathResource", properties),
        optional(prefix, "sourceUri", properties).map(URI::create),
        downloadUri,
        optional(prefix, "revision", properties),
        sha256,
        sizeBytes,
        optional(prefix, "license", properties),
        csv(optional(prefix, "capabilities", properties).orElse("")),
        csv(optional(prefix, "features", properties).orElse("")),
        files(prefix, properties, downloadUri, sha256, sizeBytes),
        backends,
        optional(prefix, "name", properties),
        optional(prefix, "description", properties),
        optional(prefix, "licenseUri", properties).map(URI::create),
        csv(optional(prefix, "domains", properties).orElse("")),
        dimensions(prefix, properties));
  }

  private static List<ModelArtifactFile> files(
      String prefix,
      Properties properties,
      Optional<URI> primaryDownloadUri,
      Optional<String> primarySha256,
      Optional<Long> primarySizeBytes) {
    int count =
        optional(prefix, "file.count", properties)
            .map(value -> parseInteger("file.count", value))
            .orElse(0);
    if (count < 0) {
      throw new ModelJarException("Invalid file.count: " + count);
    }
    if (count == 0) {
      return List.of();
    }
    URI primaryUri =
        primaryDownloadUri.orElseThrow(
            () -> new ModelJarException("Multi-file marker has no download URI: " + prefix));
    String digest =
        primarySha256.orElseThrow(
            () -> new ModelJarException("Multi-file marker has no SHA-256: " + prefix));
    long bytes =
        primarySizeBytes.orElseThrow(
            () -> new ModelJarException("Multi-file marker has no sizeBytes: " + prefix));
    record FileProperties(String path, String role, String sha256, long sizeBytes) {}
    List<FileProperties> values =
        java.util.stream.IntStream.range(0, count)
            .mapToObj(
                index -> {
                  String filePrefix = prefix + "file." + "%03d".formatted(index) + ".";
                  return new FileProperties(
                      required(filePrefix, "path", properties),
                      required(filePrefix, "role", properties),
                      required(filePrefix, "sha256", properties),
                      parseSizeBytes(required(filePrefix, "sizeBytes", properties)));
                })
            .toList();
    FileProperties primary =
        values.stream()
            .filter(file -> file.sha256().equalsIgnoreCase(digest) && file.sizeBytes() == bytes)
            .findFirst()
            .orElseThrow(
                () ->
                    new ModelJarException(
                        "Multi-file marker has no file matching its primary artifact: " + prefix));
    String primaryUriText = primaryUri.toString();
    if (!primaryUriText.endsWith(primary.path())) {
      throw new ModelJarException(
          "Multi-file marker download URI does not end with primary file path: " + prefix);
    }
    String downloadBase =
        primaryUriText.substring(0, primaryUriText.length() - primary.path().length());
    return values.stream()
        .map(
            file ->
                new ModelArtifactFile(
                    file.path(),
                    file.role(),
                    URI.create(downloadBase + file.path()),
                    file.sha256(),
                    file.sizeBytes()))
        .toList();
  }

  private static ModelDimensions dimensions(String prefix, Properties properties) {
    String dimensionPrefix = prefix + "dimension.";
    return new ModelDimensions(
        optional(dimensionPrefix, "parameterCount", properties)
            .map(value -> parseLong("parameterCount", value)),
        optional(dimensionPrefix, "contextLength", properties)
            .map(value -> parseInteger("contextLength", value)),
        optional(dimensionPrefix, "embeddingLength", properties)
            .map(value -> parseInteger("embeddingLength", value)),
        optional(dimensionPrefix, "blockCount", properties)
            .map(value -> parseInteger("blockCount", value)),
        optional(dimensionPrefix, "attentionHeadCount", properties)
            .map(value -> parseInteger("attentionHeadCount", value)),
        optional(dimensionPrefix, "keyValueHeadCount", properties)
            .map(value -> parseInteger("keyValueHeadCount", value)),
        optional(dimensionPrefix, "feedForwardLength", properties)
            .map(value -> parseInteger("feedForwardLength", value)),
        optional(dimensionPrefix, "expertCount", properties)
            .map(value -> parseInteger("expertCount", value)),
        optional(dimensionPrefix, "expertUsedCount", properties)
            .map(value -> parseInteger("expertUsedCount", value)),
        optional(dimensionPrefix, "keyLength", properties)
            .map(value -> parseInteger("keyLength", value)),
        optional(dimensionPrefix, "valueLength", properties)
            .map(value -> parseInteger("valueLength", value)),
        optional(dimensionPrefix, "attentionBlockCount", properties)
            .map(value -> parseInteger("attentionBlockCount", value)));
  }

  private static Map<String, Boolean> backends(String prefix, Properties properties) {
    String backendPrefix = prefix + "backend.";
    Map<String, Boolean> backends = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith(backendPrefix)) {
        String backend = name.substring(backendPrefix.length()).toLowerCase(Locale.ROOT);
        backends.put(backend, Boolean.parseBoolean(properties.getProperty(name)));
      }
    }
    return backends;
  }

  private static Set<String> csv(String value) {
    Set<String> values = new HashSet<>();
    for (String part : value.split(",")) {
      if (!part.isBlank()) {
        values.add(part.trim().toLowerCase(Locale.ROOT));
      }
    }
    return values;
  }

  private static String required(String prefix, String property, Properties properties) {
    return optional(prefix, property, properties)
        .orElseThrow(
            () -> new ModelJarException("Missing required property: " + prefix + property));
  }

  private static Optional<String> optional(String prefix, String property, Properties properties) {
    return Optional.ofNullable(properties.getProperty(prefix + property))
        .map(String::trim)
        .filter(s -> !s.isEmpty());
  }

  private static long parseSizeBytes(String value) {
    return parseLong("sizeBytes", value);
  }

  private static long parseLong(String property, String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid " + property + ": " + value, e);
    }
  }

  private static int parseInteger(String property, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid " + property + ": " + value, e);
    }
  }

  private static Path expandPath(String value) {
    String expanded =
        value
            .replace("${user.home}", System.getProperty("user.home"))
            .replace(
                "${modeljars.home}",
                System.getProperty(
                    "modeljars.home", System.getProperty("user.home") + "/.modeljars"));
    return Path.of(expanded).toAbsolutePath().normalize();
  }
}
