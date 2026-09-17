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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.ToolCall;
import com.integrallis.models.api.ToolSpec;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import com.integrallis.models.runtime.chat.ToolCallScanner;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Tool-call round trip through the exact chat template of every tool-qualified model, without
 * running a model.
 *
 * <p>For each qualified tool entry bundled in this runtime, every chat template the runtime can
 * select for that artifact (the tool qualification's, and that of any production RAG qualification
 * for the same artifact, since {@code ModelJars.open} picks among both) renders a conversation that
 * declares a tool and contains an assistant tool call. The rendered assistant turn is then scanned
 * back with the same template's {@code ToolSyntax}, and the call name and arguments must survive
 * exactly. A template whose rendered calls its own scanner cannot recover makes every downstream
 * tool-calling measurement meaningless, so this gate fails the build.
 */
class ToolCallTemplateRoundTripTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final ToolSpec WEATHER =
      new ToolSpec(
          "get_weather",
          "Returns the current weather for a city.",
          """
          {"type": "object", "properties": {
            "city": {"type": "string"},
            "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]},
            "days": {"type": "integer"},
            "hourly": {"type": "boolean"},
            "location": {"type": "object", "properties": {
              "lat": {"type": "number"}, "lon": {"type": "number"}}},
            "note": {"type": "string"}},
           "required": ["city"]}
          """);

  private record Case(String label, String argumentsJson) {}

  private static final List<Case> CASES =
      List.of(
          new Case("single string argument", "{\"city\": \"Paris\"}"),
          new Case(
              "typed, nested, escaped and non-ASCII arguments",
              "{\"city\": \"São Paulo \\\"Centro\\\"\", \"unit\": \"celsius\", \"days\": 3,"
                  + " \"hourly\": false, \"location\": {\"lat\": -23.55, \"lon\": -46.63},"
                  + " \"note\": \"braces {like this} and [brackets] inside a string\"}"));

  @Test
  void theGateCoversEveryToolQualifiedModel() {
    ModelToolQualificationRegistry registry = ModelToolQualificationRegistry.fromClasspath();
    assertFalse(registry.qualified().isEmpty(), "no tool-qualified models were bundled");
    assertEquals(registry.qualifiedModels(), registry.qualified().size());
  }

  @TestFactory
  Stream<DynamicTest> everyToolQualifiedTemplateRoundTripsItsOwnToolCalls() {
    ModelToolQualificationRegistry tools = ModelToolQualificationRegistry.fromClasspath();
    ModelRagQualificationRegistry rag = ModelRagQualificationRegistry.fromClasspath();
    List<DynamicTest> tests = new ArrayList<>();
    for (ModelToolQualification qualification : tools.qualified()) {
      Set<String> templates = new LinkedHashSet<>();
      templates.add(qualification.promptTemplate());
      rag.qualified().stream()
          .filter(entry -> entry.modelId().equals(qualification.modelId()))
          .filter(entry -> entry.artifactSha256().equals(qualification.artifactSha256()))
          .filter(ModelExecutionQualification::productionUsable)
          .map(ModelRagQualification::promptTemplate)
          .forEach(templates::add);
      for (String recorded : templates) {
        for (Case example : CASES) {
          tests.add(
              DynamicTest.dynamicTest(
                  qualification.modelId() + " [" + recorded + "] " + example.label(),
                  () -> assertRoundTrip(recorded, example)));
        }
      }
    }
    return tests.stream();
  }

  @Test
  void theGateFailsWhenTheScannerDoesNotMatchTheRenderedSyntax() {
    // Negative control: Llama 3 renders a bare {"name", "parameters"} object, which the Qwen
    // tagged-JSON scanner used by chatml-no-think must not recover. If this ever passes, the gate
    // above has stopped being able to fail.
    Case example = CASES.getFirst();
    AssertionError failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            AssertionError.class,
            () -> assertRoundTrip(ChatTemplate.LLAMA3, ChatTemplate.CHATML_NO_THINK, example));
    assertTrue(failure.getMessage().contains("recovered"), failure.getMessage());
  }

  private static void assertRoundTrip(String recordedTemplate, Case example) throws Exception {
    ChatTemplate template = QualifiedChatTemplates.resolve(recordedTemplate);
    assertRoundTrip(template, template, example);
  }

  private static void assertRoundTrip(ChatTemplate template, ChatTemplate scanner, Case example)
      throws Exception {
    assertTrue(
        scanner.canParseToolCallsWithSchemas(),
        "chat template " + scanner.id() + " cannot recover tool calls at all");

    ToolCall call = ToolCall.of(0, WEATHER.name(), example.argumentsJson());
    List<ChatMessage> before = List.of(ChatMessage.user("What is the weather?"));
    List<ChatMessage> after =
        List.of(before.getFirst(), ChatMessage.assistantToolCalls("", List.of(call)));
    List<ModelPrompt.Segment> prompt = template.render(before, List.of(WEATHER)).segments();
    List<ModelPrompt.Segment> conversation = template.render(after, List.of(WEATHER)).segments();

    // Segments the two renderings share (tool declarations, the user turn) are excluded, so the
    // declared example format in a tool preamble can never satisfy the scan. Comparing whole
    // segments rather than characters keeps a generation prompt that happens to share leading
    // characters with the assistant turn (for example "<think>" and "<tool_call>") from cutting
    // into the rendered call.
    int shared = 0;
    while (shared < Math.min(prompt.size(), conversation.size())
        && prompt.get(shared).equals(conversation.get(shared))) {
      shared++;
    }
    String assistantTurn =
        conversation.subList(shared, conversation.size()).stream()
            .map(ModelPrompt.Segment::text)
            .collect(Collectors.joining());
    assertTrue(
        assistantTurn.contains(WEATHER.name()),
        "rendered assistant turn does not contain the call: " + assistantTurn);

    List<ToolCallScanner.Result> scans = new ArrayList<>();
    scans.add(ToolCallScanner.scan(assistantTurn, scanner.toolSyntax(), List.of(WEATHER)));
    if (scanner.canParseToolCalls()) {
      scans.add(ToolCallScanner.scan(assistantTurn, scanner.toolSyntax()));
    }
    for (ToolCallScanner.Result scan : scans) {
      assertEquals(
          1,
          scan.toolCalls().size(),
          template.id() + " recovered " + scan + " from " + assistantTurn);
      ToolCall recovered = scan.toolCalls().getFirst();
      assertEquals(call.name(), recovered.name(), template.id() + " call name");
      JsonNode expected = JSON.readTree(call.argumentsJson());
      JsonNode actual = JSON.readTree(recovered.argumentsJson());
      assertEquals(
          expected,
          actual,
          template.id() + " arguments from " + assistantTurn + " -> " + recovered.argumentsJson());
    }
  }
}
