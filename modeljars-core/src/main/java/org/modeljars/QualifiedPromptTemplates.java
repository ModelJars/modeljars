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

import java.util.Locale;
import java.util.Map;

/**
 * Maps the prompt template a qualification recorded to the identifier of the runtime chat template
 * that renders it.
 *
 * <p>A qualification records the envelope its harness applied. Most harness envelopes are runtime
 * chat templates and share their identifiers, but a harness may record a variant that only the
 * qualification workload can render. Those variants are mapped here, explicitly and one by one, to
 * the runtime template that renders the same conversation turns:
 *
 * <ul>
 *   <li>{@code granite-documents} is the Granite 4.x documents request used by the RAG harness
 *       ({@code GraniteDocumentsPrompt} in Models). Its user and assistant turns, role markers,
 *       turn terminators, and closing assistant marker are those of the runtime {@code granite}
 *       template; the only difference is the system turn, where the harness places retrieved
 *       evidence in the {@code <documents>} block. A runtime conversation carries no harness
 *       evidence, so it renders through {@code granite}.
 * </ul>
 *
 * <p>Every other identifier is returned unchanged, for the runtime to accept or reject. Nothing
 * here substitutes a default: an unknown template must fail rather than fall back to an unformatted
 * prompt, which produces plausible output from a model that was never qualified on it.
 */
public final class QualifiedPromptTemplates {
  private static final Map<String, String> HARNESS_VARIANTS =
      Map.of("granite-documents", "granite");

  private QualifiedPromptTemplates() {}

  /**
   * Returns the runtime chat-template identifier for a recorded qualification prompt template.
   *
   * @param promptTemplate identifier recorded by the qualification run, possibly {@code null}
   * @return the mapped runtime identifier, or {@code promptTemplate} unchanged when it is not a
   *     harness-only variant
   */
  public static String runtimeChatTemplateId(String promptTemplate) {
    if (promptTemplate == null) {
      return null;
    }
    return HARNESS_VARIANTS.getOrDefault(
        promptTemplate.trim().toLowerCase(Locale.ROOT), promptTemplate);
  }
}
