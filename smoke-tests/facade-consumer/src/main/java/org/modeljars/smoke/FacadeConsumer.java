package org.modeljars.smoke;

import java.io.IOException;
import org.modeljars.ModelVersion;

public final class FacadeConsumer {
  private static final String REGISTRY_RESOURCE = "META-INF/modeljars/registry.properties";

  private FacadeConsumer() {}

  public static void main(String[] args) throws IOException {
    String parsedVersion = ModelVersion.parse("1.2.3").toString();
    if (!"1.2.3".equals(parsedVersion)) {
      throw new IllegalStateException("Facade did not expose the ModelJars API");
    }

    var registries =
        Thread.currentThread().getContextClassLoader().getResources(REGISTRY_RESOURCE);
    if (registries.hasMoreElements()) {
      throw new IllegalStateException("Facade must not pull the aggregate catalog transitively");
    }
  }
}
