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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Java runtime and startup arguments required by a measured model performance profile.
 *
 * @param runtime normalized JVM or compiler identifier
 * @param javaFeature required Java feature version
 * @param jvmArguments required JVM startup arguments
 */
public record JavaLaunchProfile(String runtime, int javaFeature, List<String> jvmArguments) {

  /** Validates and normalizes the runtime identifier and JVM arguments. */
  public JavaLaunchProfile {
    if (runtime == null || runtime.isBlank()) {
      throw new IllegalArgumentException("runtime must not be blank");
    }
    runtime = runtime.trim().toLowerCase(Locale.ROOT);
    if (javaFeature <= 0) {
      throw new IllegalArgumentException("javaFeature must be > 0");
    }
    Objects.requireNonNull(jvmArguments, "jvmArguments");
    jvmArguments =
        jvmArguments.stream()
            .map(argument -> requireJvmArgument(argument, "jvmArguments"))
            .toList();
    if (jvmArguments.stream().distinct().count() != jvmArguments.size()) {
      throw new IllegalArgumentException("jvmArguments must not contain duplicates");
    }
  }

  /**
   * Builds process arguments without shell parsing or quoting.
   *
   * @param javaExecutable Java executable path or command
   * @param applicationArguments arguments appended after the JVM options
   * @return immutable process argument list
   */
  public List<String> command(String javaExecutable, List<String> applicationArguments) {
    if (javaExecutable == null || javaExecutable.isBlank()) {
      throw new IllegalArgumentException("javaExecutable must not be blank");
    }
    Objects.requireNonNull(applicationArguments, "applicationArguments");
    List<String> command = new ArrayList<>(1 + jvmArguments.size() + applicationArguments.size());
    command.add(javaExecutable.trim());
    command.addAll(jvmArguments);
    applicationArguments.forEach(
        argument -> command.add(Objects.requireNonNull(argument, "application argument")));
    return List.copyOf(command);
  }

  /**
   * Returns required startup arguments absent from the supplied JVM input arguments.
   *
   * @param actualArguments JVM input arguments active in the current process
   * @return required arguments not present in {@code actualArguments}
   */
  public List<String> missingArguments(Collection<String> actualArguments) {
    Objects.requireNonNull(actualArguments, "actualArguments");
    return jvmArguments.stream().filter(argument -> !actualArguments.contains(argument)).toList();
  }

  private static String requireJvmArgument(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not contain blank arguments");
    }
    String argument = value.trim();
    if (!argument.startsWith("-")) {
      throw new IllegalArgumentException(name + " entries must be JVM options: " + argument);
    }
    return argument;
  }
}
