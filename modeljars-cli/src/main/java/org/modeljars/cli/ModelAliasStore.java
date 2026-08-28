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
package org.modeljars.cli;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.modeljars.ModelJarCache;

/** Persistent, shell-friendly nicknames for exact catalog model aliases. */
final class ModelAliasStore {
  static final String PATH_PROPERTY = "modeljars.aliases.file";
  static final String PATH_ENVIRONMENT = "MODELJARS_ALIASES_FILE";
  private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final Path path;

  ModelAliasStore(Path path) {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
  }

  static ModelAliasStore defaults() {
    String configured = System.getProperty(PATH_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(PATH_ENVIRONMENT);
    }
    if (configured != null && !configured.isBlank()) {
      return new ModelAliasStore(Path.of(configured));
    }
    Path cache = ModelJarCache.defaultDirectory();
    Path home = cache.getParent();
    return new ModelAliasStore((home == null ? cache : home).resolve("aliases.properties"));
  }

  synchronized Map<String, String> aliases() {
    if (!Files.isRegularFile(path)) {
      return Map.of();
    }
    if (Files.isSymbolicLink(path)) {
      throw new IllegalStateException("Refusing to read model aliases through a symbolic link");
    }
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to read model aliases from " + path, failure);
    }
    Map<String, String> result = new LinkedHashMap<>();
    properties.stringPropertyNames().stream()
        .sorted()
        .forEach(name -> result.put(name, properties.getProperty(name)));
    return Collections.unmodifiableMap(result);
  }

  synchronized void set(String name, String target, Set<String> catalogNames) {
    String normalizedName = requireName(name);
    String normalizedTarget = requireName(target);
    Objects.requireNonNull(catalogNames, "catalogNames");
    if (catalogNames.stream()
        .anyMatch(catalogName -> catalogName.equalsIgnoreCase(normalizedName))) {
      throw new IllegalArgumentException(
          "Nickname clashes with a catalog model name: " + normalizedName);
    }
    Map<String, String> updated = new LinkedHashMap<>(aliases());
    updated.put(normalizedName, normalizedTarget);
    write(updated);
  }

  synchronized boolean remove(String name) {
    String normalizedName = requireName(name);
    Map<String, String> updated = new LinkedHashMap<>(aliases());
    boolean removed = updated.remove(normalizedName) != null;
    if (removed) {
      write(updated);
    }
    return removed;
  }

  Path path() {
    return path;
  }

  private void write(Map<String, String> aliases) {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IllegalStateException("Model alias file must have a parent directory");
    }
    try {
      Files.createDirectories(parent);
      if (Files.exists(path) && Files.isSymbolicLink(path)) {
        throw new IllegalStateException("Refusing to replace a symbolic-link model alias file");
      }
      Path temporary = Files.createTempFile(parent, ".modeljars-aliases-", ".tmp");
      try {
        StringBuilder content = new StringBuilder();
        aliases.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(
                entry ->
                    content
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append('\n'));
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
          Files.move(
              temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Unable to write model aliases to " + path, failure);
    }
  }

  private static String requireName(String value) {
    if (value == null || !VALID_NAME.matcher(value.strip()).matches()) {
      throw new IllegalArgumentException(
          "Nickname must contain only letters, numbers, '.', '_' or '-' and must not be blank");
    }
    return value.strip();
  }
}
