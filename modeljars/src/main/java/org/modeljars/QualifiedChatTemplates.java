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

/**
 * Resolves the prompt template a qualification recorded to the chat template the runtime renders.
 *
 * <p>Harness-only variants are mapped by {@link QualifiedPromptTemplates}; every other identifier
 * must be a runtime {@link ChatTemplate} id. Unknown identifiers are rejected and never fall back
 * to {@link ChatTemplate#RAW}.
 */
public final class QualifiedChatTemplates {
  private QualifiedChatTemplates() {}

  /**
   * Resolves a recorded qualification prompt template.
   *
   * @param promptTemplate identifier recorded by the qualification run
   * @return runtime chat template
   * @throws IllegalArgumentException if no runtime chat template renders the recorded template
   */
  public static ChatTemplate resolve(String promptTemplate) {
    return ChatTemplate.parse(QualifiedPromptTemplates.runtimeChatTemplateId(promptTemplate));
  }
}
