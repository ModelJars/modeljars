package org.modeljars;

import java.net.URI;

/** Common evidence required to execute one immutable generative model artifact. */
public interface ModelExecutionQualification {

  String modelId();

  String model();

  String backend();

  String backendVersion();

  String workload();

  String promptTemplate();

  String artifactSha256();

  long artifactSizeBytes();

  String reportPath();

  URI reportUri();

  String reportSha256();

  String verdict();

  boolean qualified();

  int attempts();

  double p95EndToEndMillis();

  boolean productionUsable();

  boolean matches(ModelJarDescriptor descriptor);
}
