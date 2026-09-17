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

import com.integrallis.models.runtime.chat.ChatTemplate;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the prompt template a qualification recorded to the chat template the runtime renders.
 *
 * <p>A qualification records the envelope its harness applied. Most harness envelopes are runtime
 * chat templates and share their identifiers, but a harness may record a variant that only the
 * qualification workload can render. Those variants are mapped here, explicitly and one by one, to
 * the runtime template that renders the same conversation turns:
 *
 * <ul>
 *   <li>{@code granite-documents} is the Granite 4.x documents request used by the RAG harness
 *       ({@code GraniteDocumentsPrompt} in Models). Its user and assistant turns, role markers,
 *       turn terminators, and closing assistant marker are those of {@link ChatTemplate#GRANITE};
 *       the only difference is the system turn, where the harness places retrieved evidence in the
 *       {@code <documents>} block. A runtime conversation carries no harness evidence, so it
 *       renders through {@link ChatTemplate#GRANITE}.
 * </ul>
 *
 * <p>Identifiers that are neither runtime templates nor mapped variants are rejected. They never
 * fall back to {@link ChatTemplate#RAW}: an unformatted prompt produces plausible output from a
 * model that was not qualified on it.
 */
public final class QualifiedChatTemplates {
  private static final Map<String, ChatTemplate> HARNESS_VARIANTS =
      Map.of("granite-documents", ChatTemplate.GRANITE);

  private QualifiedChatTemplates() {}

  /**
   * Resolves a recorded qualification prompt template.
   *
   * @param promptTemplate identifier recorded by the qualification run
   * @return runtime chat template
   * @throws IllegalArgumentException if no runtime chat template renders the recorded template
   */
  public static ChatTemplate resolve(String promptTemplate) {
    if (promptTemplate != null) {
      ChatTemplate variant = HARNESS_VARIANTS.get(promptTemplate.trim().toLowerCase(Locale.ROOT));
      if (variant != null) {
        return variant;
      }
    }
    return ChatTemplate.parse(promptTemplate);
  }
}
