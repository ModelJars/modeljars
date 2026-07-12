package org.modeljars;

/** Minimal command-line entry point for build tools that install a ModelJars artifact. */
public final class ModelJarInstallerCli {
  private ModelJarInstallerCli() {}

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(
          "Usage: ModelJarInstallerCli <source> [--version <range>] [--variant <name>]"
              + " [--backend <name>] [--capability <name>]");
    }

    ModelJarRequirement.Builder requirement = ModelJarRequirement.forSource(args[0]);
    for (int index = 1; index < args.length; index += 2) {
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for " + args[index]);
      }
      String option = args[index];
      String value = args[index + 1];
      switch (option) {
        case "--version" -> requirement.versionRange(value);
        case "--variant" -> requirement.variant(value);
        case "--backend" -> requirement.backend(value);
        case "--capability" -> requirement.capability(value);
        default -> throw new IllegalArgumentException("Unknown option: " + option);
      }
    }

    var installer = new ModelJarInstaller(ModelJarRegistry.fromClasspath());
    System.out.println(installer.install(requirement.build()));
  }
}
