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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.modeljars.catalog.Cactus_Compute_Needle2_Cact_Cq2_Mixed.MODEL;

import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.ToolSpec;
import com.integrallis.models.runtime.ToolCallTokenConstraints;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import com.integrallis.models.runtime.chat.ToolCallScanner;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Needle2CactIntegrationTest {
  private static final String ARTIFACT_PROPERTY = "modeljars.fixtures.needle2Cact";

  @Test
  void verifiesAndCallsAToolThroughThePublishedModelJarsContract() {
    String configured = System.getProperty(ARTIFACT_PROPERTY, "").trim();
    Assumptions.assumeFalse(configured.isEmpty(), () -> "Set -D" + ARTIFACT_PROPERTY);
    Path artifact = Path.of(configured).toAbsolutePath().normalize();
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarDescriptor descriptor = registry.resolve(MODEL).orElseThrow();
    ModelJarInstaller installer = new ModelJarInstaller(registry);
    ModelJars modelJars =
        new ModelJars(
            registry,
            ModelRagQualificationRegistry.fromClasspath(),
            ModelPerformanceProfileRegistry.fromClasspath(),
            (candidate, options) -> installer.verifyCached(candidate, artifact),
            ModelJars::loadBackend,
            Map::of,
            List::of);

    try (ModelJarRuntime runtime =
        modelJars.loadRuntime(
            MODEL, ModelLoadOptions.builder().backend(ModelBackend.JAVA).offline(true).build())) {
      ToolSpec sendEmail =
          new ToolSpec(
              "send_email",
              "Send an email.",
              "{\"type\":\"object\",\"properties\":{"
                  + "\"to\":{\"type\":\"string\",\"description\":\"Email address.\"},"
                  + "\"subject\":{\"type\":\"string\"},"
                  + "\"body\":{\"type\":\"string\"}},"
                  + "\"required\":[\"to\",\"subject\",\"body\"]}");
      List<ToolSpec> tools = List.of(sendEmail);
      String query =
          "send the receipt to finance@cactus.dev with subject expenses: "
              + "Blue Bottle Coffee, $14.50, August 4th";
      var prompt = runtime.chatTemplate().render(List.of(ChatMessage.user(query)), tools);
      var constraint =
          ToolCallTokenConstraints.compile(
                  runtime.tokenizer(),
                  runtime.chatTemplate().toolSyntax(),
                  tools,
                  ignored -> List.of())
              .orElseThrow();
      String output =
          runtime
              .pipeline()
              .generate(
                  prompt,
                  SamplingOptions.builder().temperature(0).maxTokens(256).build(),
                  constraint);
      var calls = ToolCallScanner.scan(output, runtime.chatTemplate().toolSyntax()).toolCalls();

      assertEquals("cact", descriptor.format());
      assertEquals(ChatTemplate.NEEDLE2, runtime.chatTemplate());
      assertTrue(runtime.toolQualification().orElseThrow().productionUsable());
      assertEquals(1, calls.size());
      assertEquals("send_email", calls.getFirst().name());
      assertTrue(calls.getFirst().argumentsJson().contains("finance@cactus.dev"));
      assertTrue(calls.getFirst().argumentsJson().contains("\"subject\":\"expenses\""));
    }
  }
}
