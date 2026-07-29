package org.modeljars.smoke;

import com.integrallis.models.api.SamplingOptions;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelVersion;

public final class FacadeConsumer {
  private FacadeConsumer() {}

  public static void main(String[] args) {
    String parsedVersion = ModelVersion.parse("1.2.3").toString();
    if (!"1.2.3".equals(parsedVersion)) {
      throw new IllegalStateException("Facade did not expose the ModelJars API");
    }

    int descriptorCount = ModelJarRegistry.fromClasspath().descriptors().size();
    if (descriptorCount < 100) {
      throw new IllegalStateException(
          "Facade must expose the aggregate launch catalog; found " + descriptorCount + " models");
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
