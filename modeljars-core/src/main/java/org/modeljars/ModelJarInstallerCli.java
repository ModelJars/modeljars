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
