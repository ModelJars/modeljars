package org.modeljars;

/** Controlled host and JVM identity captured with a model qualification. */
public record ModelQualificationEnvironment(
    String hostname,
    String osName,
    String osVersion,
    String architecture,
    String cpuModel,
    int availableProcessors,
    long totalMemoryBytes,
    long maxHeapBytes,
    String javaVersion,
    String javaVendor,
    String vmName) {

  public ModelQualificationEnvironment {
    hostname = requireText(hostname, "hostname");
    osName = requireText(osName, "osName");
    osVersion = requireText(osVersion, "osVersion");
    architecture = requireText(architecture, "architecture");
    cpuModel = requireText(cpuModel, "cpuModel");
    if (availableProcessors < 1) {
      throw new IllegalArgumentException("availableProcessors must be positive");
    }
    if (totalMemoryBytes < 1) {
      throw new IllegalArgumentException("totalMemoryBytes must be positive");
    }
    if (maxHeapBytes < 1) {
      throw new IllegalArgumentException("maxHeapBytes must be positive");
    }
    javaVersion = requireText(javaVersion, "javaVersion");
    javaVendor = requireText(javaVendor, "javaVendor");
    vmName = requireText(vmName, "vmName");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
