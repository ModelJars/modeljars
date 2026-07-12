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

  public static PropertiesModelJarRegistry load(Path path) {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars registry: " + path, e);
    }
    return fromProperties(properties);
  }

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
        optional(prefix, "sourceUri", properties).map(URI::create),
        optional(prefix, "downloadUri", properties).map(URI::create),
        optional(prefix, "revision", properties),
        optional(prefix, "sha256", properties),
        optional(prefix, "sizeBytes", properties).map(PropertiesModelJarRegistry::parseSizeBytes),
        optional(prefix, "license", properties),
        csv(optional(prefix, "capabilities", properties).orElse("")),
        backends);
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
        .orElseThrow(() -> new ModelJarException("Missing required property: " + prefix + property));
  }

  private static Optional<String> optional(String prefix, String property, Properties properties) {
    return Optional.ofNullable(properties.getProperty(prefix + property))
        .map(String::trim)
        .filter(s -> !s.isEmpty());
  }

  private static long parseSizeBytes(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ModelJarException("Invalid sizeBytes: " + value, e);
    }
  }

  private static Path expandPath(String value) {
    String expanded =
        value.replace("${user.home}", System.getProperty("user.home"))
            .replace("${modeljars.home}", System.getProperty("modeljars.home", System.getProperty("user.home") + "/.modeljars"));
    return Path.of(expanded).toAbsolutePath().normalize();
  }
}
