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
package org.modeljars.composite.qwen3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.ToolSpec;
import com.integrallis.models.runtime.ToolCallTokenConstraints;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;
import com.integrallis.models.runtime.chat.VirtualChatModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class Qwen3ChatToolsIntegrationIT {
  private static final ToolSpec WEATHER =
      new ToolSpec(
          "get-weather-for-zipcode",
          "Gets weather for a given zipcode",
          """
          {"type":"object","properties":{"zipcode":{"type":"string"}},"required":["zipcode"]}
          """);
  private static final SamplingOptions OPTIONS =
      SamplingOptions.builder().temperature(0.0f).maxTokens(64).build();

  @Test
  void runsThePublishedRecipeThroughModelJars() {
    VirtualChatModel.ConstraintFactory constraints =
        (session, turn) ->
            ToolCallTokenConstraints.compile(
                session.tokenizer(),
                ChatTemplate.CHATML_NO_THINK.toolSyntax(),
                turn.tools(),
                ignored -> List.of("{\"zipcode\":\"88252\"}"));

    try (var hybrid = Qwen3ChatTools.open(constraints);
        var conversation =
            hybrid.openSession(
                List.of(ChatMessage.system("Answer directly and use tools when asked.")))) {
      var greeting =
          conversation.generate(ChatMessage.user("Reply with exactly: READY"), List.of(), OPTIONS);
      var call =
          conversation.generate(
              ChatMessage.user("What is the weather for 88252?"), List.of(WEATHER), OPTIONS);
      var answer =
          conversation.generate(
              ChatMessage.tool(
                  "get-weather-for-zipcode",
                  "{\"zipcode\":\"88252\",\"conditions\":\"Raining cats and dogs\","
                      + "\"temperatureInFahrenheit\":78}"),
              List.of(),
              OPTIONS);

      assertEquals("chat", greeting.memberId());
      assertTrue(greeting.content().contains("READY"));
      assertEquals("tools", call.memberId());
      assertEquals("get-weather-for-zipcode", call.toolCalls().getFirst().name());
      assertTrue(call.toolCalls().getFirst().argumentsJson().contains("88252"));
      assertEquals("chat", answer.memberId());
      assertTrue(answer.content().contains("78"));
      assertTrue(answer.content().toLowerCase(java.util.Locale.ROOT).contains("rain"));
    }
  }
}
