// Renders a model's own tokenizer.chat_template (Jinja) so catalog tooling can read what the
// template emits instead of recognising template idioms by pattern.
//
// @huggingface/jinja is the Jinja implementation Hugging Face ships for rendering these templates
// in JavaScript; its version is pinned exactly in package.json. Rendering reads the template and
// the values passed in; it performs no I/O and runs no model.

import { Template } from "@huggingface/jinja";

export function renderChatTemplate(template, context) {
  return new Template(template).render(context);
}
