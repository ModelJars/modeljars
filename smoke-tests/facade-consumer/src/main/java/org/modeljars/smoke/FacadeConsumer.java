package org.modeljars.smoke;

import com.integrallis.models.api.SamplingOptions;
import java.util.stream.Collectors;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelRagQualification;
import org.modeljars.ModelRagQualificationRegistry;
import org.modeljars.ModelVersion;

public final class FacadeConsumer {
  private FacadeConsumer() {}

  public static void main(String[] args) {
    String parsedVersion = ModelVersion.parse("1.2.3").toString();
    if (!"1.2.3".equals(parsedVersion)) {
      throw new IllegalStateException("Facade did not expose the ModelJars API");
    }

    var descriptors = ModelJarRegistry.fromClasspath().descriptors();
    var qualifications = ModelRagQualificationRegistry.fromClasspath();
    if (descriptors.size() != qualifications.qualifiedModels()) {
      throw new IllegalStateException(
          "Facade catalog and qualification counts differ: "
              + descriptors.size()
              + " descriptors, "
              + qualifications.qualifiedModels()
              + " qualified artifacts");
    }

    var descriptorIds =
        descriptors.stream().map(descriptor -> descriptor.alias()).collect(Collectors.toSet());
    var qualifiedIds =
        qualifications.qualified().stream()
            .map(ModelRagQualification::modelId)
            .collect(Collectors.toSet());
    if (!descriptorIds.equals(qualifiedIds)) {
      throw new IllegalStateException(
          "Facade must expose exactly the qualified model identities: "
              + descriptorIds
              + " != "
              + qualifiedIds);
    }

    if (descriptors.stream()
        .anyMatch(descriptor -> qualifications.qualificationsFor(descriptor).isEmpty())) {
      throw new IllegalStateException(
          "Facade contains a descriptor without exact artifact qualification evidence");
    }

    if (SamplingOptions.builder().maxTokens(8).build().maxTokens() != 8) {
      throw new IllegalStateException("Facade did not expose the Models API");
    }

    try {
      Class.forName(
          "com.integrallis.models.backend.nativekernel.RustFfmBackend",
          false,
          FacadeConsumer.class.getClassLoader());
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("Facade did not provide the native Models backend", exception);
    }
  }
}
