package org.modeljars.smoke;

import static org.modeljars.catalog.Qwen3_0_6b_Q4_0.MODEL;

import com.integrallis.models.api.SamplingOptions;
import java.util.stream.Collectors;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualification;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelVersion;

public final class JvmRuntimeConsumer {
  private JvmRuntimeConsumer() {}

  public static void main(String[] args) {
    String parsedVersion = ModelVersion.parse("1.2.3").toString();
    if (!"1.2.3".equals(parsedVersion)) {
      throw new IllegalStateException("JVM Runtime did not expose the ModelJars API");
    }

    var descriptors = ModelJarRegistry.fromClasspath().descriptors();
    var qualifications = ModelRagQualificationRegistry.fromClasspath();
    if (descriptors.size() != 1) {
      throw new IllegalStateException(
          "The selected marker must contribute exactly one descriptor: " + descriptors.size());
    }
    if (!"qwen3_0_6b_q4_0"
        .equals(ModelJarRegistry.fromClasspath().resolve(MODEL).orElseThrow().alias())) {
      throw new IllegalStateException("The marker's generated reference did not resolve");
    }

    var descriptorIds =
        descriptors.stream().map(descriptor -> descriptor.alias()).collect(Collectors.toSet());
    var qualifiedIds =
        descriptors.stream()
            .flatMap(descriptor -> qualifications.qualificationsFor(descriptor).stream())
            .map(ModelRagQualification::modelId)
            .collect(Collectors.toSet());
    if (!descriptorIds.equals(qualifiedIds)) {
      throw new IllegalStateException(
          "The selected marker must expose the same descriptor and qualification identity: "
              + descriptorIds
              + " != "
              + qualifiedIds);
    }

    if (descriptors.stream()
        .anyMatch(descriptor -> qualifications.qualificationsFor(descriptor).isEmpty())) {
      throw new IllegalStateException(
          "The selected marker contains a descriptor without exact qualification evidence");
    }

    if (SamplingOptions.builder().maxTokens(8).build().maxTokens() != 8) {
      throw new IllegalStateException("JVM Runtime did not expose the Models API");
    }

    try {
      Class.forName(
          "com.integrallis.models.backend.nativekernel.RustFfmBackend",
          false,
          JvmRuntimeConsumer.class.getClassLoader());
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException(
          "JVM Runtime did not provide the native Models backend", exception);
    }
  }
}
