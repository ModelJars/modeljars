package org.modeljars;

import static org.modeljars.catalog.Qwen_Qwen2_5_0_5b_Instruct_Bf16.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.runtime.chat.ChatMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Qwen25SafetensorsIntegrationTest {
  private static final String DIRECTORY_PROPERTY =
      "modeljars.fixtures.qwen25SafetensorsDirectory";
  @Test
  void verifiesOpensAndGeneratesThroughTheModelJarsRuntime() {
    String configured = System.getProperty(DIRECTORY_PROPERTY, "").trim();
    Assumptions.assumeFalse(configured.isEmpty(), () -> "Set -D" + DIRECTORY_PROPERTY);
    Path directory = Path.of(configured).toAbsolutePath().normalize();
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = registry.resolve(MODEL).orElseThrow();
    assertEquals(4, descriptor.files().size());
    assertEquals("safetensors", descriptor.format());
    ModelJarInstaller installer = new ModelJarInstaller(registry);
    ModelJars modelJars =
        new ModelJars(
            registry,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) ->
                installer.verifyCached(candidate, directory.resolve("model.safetensors")),
            ModelJars::loadBackend,
            Map::of,
            List::of);

    try (ModelJarRuntime runtime =
        modelJars.loadRuntime(
            MODEL,
            ModelLoadOptions.builder().backend(ModelBackend.JAVA).offline(true).build())) {
      assertEquals(descriptor, runtime.descriptor());
      assertTrue(runtime.qualification().productionUsable());
      assertEquals("qwen2", runtime.metadata().modelFamily());
      var prompt =
          runtime.chatTemplate().render(List.of(ChatMessage.user("Name one JVM language.")));
      String answer =
          runtime.model().generate(
              prompt, SamplingOptions.builder().temperature(0).maxTokens(8).build());
      assertFalse(answer.isBlank());
    }
  }
}
