// The assembly recipes behind the catalog's compositions. A hybrid is a catalog category; what makes
// one is a named recipe, and recipes need not share architecture with one another. The catalog names
// a recipe on every composition (catalog/compositions.json, validated in build.gradle.kts against
// this list), the card and the detail page show it, and /recipes/ explains each one.
export const RECIPES = Object.freeze({
  "shared-prefix-activated-lora": Object.freeze({
    id: "shared-prefix-activated-lora",
    name: "Shared-prefix activated LoRA",
    category: "hybrid",
    summary:
      "One base model and an activated low-rank adapter. Ordinary chat and the specialist branch fork from the same physical KV-cache prefix, so the second behaviour costs no second model in memory.",
  }),
  "option-logit-readout": Object.freeze({
    id: "option-logit-readout",
    name: "Option-logit readout",
    category: "recipe",
    summary:
      "A frozen base model read in one forward pass: the answer is the calibrated probability over lettered options, never generated text.",
  }),
  "entropy-gated-logit-fusion": Object.freeze({
    id: "entropy-gated-logit-fusion",
    name: "Entropy-gated logit fusion",
    category: "hybrid",
    summary:
      "A lead model that always decodes and same-tokenizer specialists consulted only where the lead's next-token entropy is high, their log-probabilities combined as a product of experts.",
  }),
});

export const RECIPES_PAGE = "/recipes/";

/** The recipe a catalog entry names, or null for plain models and unknown names. */
export function recipeProfile(model) {
  const recipe = RECIPES[model?.recipe];
  if (!recipe) return null;
  return { ...recipe, href: `${RECIPES_PAGE}#${recipe.id}` };
}
