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

/** Minimal command-line entry point for build tools that install a ModelJars artifact. */
public final class ModelJarInstallerCli {
  private ModelJarInstallerCli() {}

  /**
   * Resolves and installs the model selected by command-line arguments.
   *
   * @param args model source followed by optional selection constraints
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Usage: ModelJarInstallerCli <source> [--version <range>] [--variant <name>]"
              + " [--backend <name>] [--capability <name>]");
    }

    ModelJar modelJar = ModelJar.of(args[0]);
    for (int index = 1; index < args.length; index += 2) {
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for " + args[index]);
      }
      String option = args[index];
      String value = args[index + 1];
      modelJar =
          switch (option) {
            case "--version" -> modelJar.version(value);
            case "--variant" -> modelJar.variant(value);
            case "--backend" -> modelJar.backend(value);
            case "--capability" -> modelJar.capability(value);
            default -> throw new IllegalArgumentException("Unknown option: " + option);
          };
    }

    var installer = new ModelJarInstaller(ModelJarRegistry.fromClasspath());
    System.out.println(installer.install(modelJar));
  }
}
