import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { renderRecipe } from "./model-detail.js";
import { RECIPES, RECIPES_PAGE, recipeProfile } from "./recipes.js";

const read = (path) => readFile(new URL(`../../${path}`, import.meta.url), "utf8");

test("a composition's recipe resolves to a name, a category and a link to its explanation", () => {
  const profile = recipeProfile({ kind: "hybrid", recipe: "shared-prefix-activated-lora" });
  assert.equal(profile.name, "Shared-prefix activated LoRA");
  assert.equal(profile.category, "hybrid");
  assert.equal(profile.href, `${RECIPES_PAGE}#shared-prefix-activated-lora`);
  assert.ok(profile.summary.length > 40);
});

test("plain models and unknown recipe names render no recipe", () => {
  assert.equal(recipeProfile({ kind: undefined, architecture: "qwen3" }), null);
  assert.equal(recipeProfile({ kind: "hybrid", recipe: "weight-merge" }), null);
  assert.equal(recipeProfile(undefined), null);
});

test("every catalog composition names a recipe this module knows", async () => {
  const compositions = JSON.parse(await read("catalog/compositions.json")).compositions;
  assert.ok(compositions.length >= 2);
  for (const composition of compositions) {
    const profile = recipeProfile(composition);
    assert.ok(profile, `${composition.id} must name a known recipe, got ${composition.recipe}`);
    // The recipe's category is the catalog kind: a hybrid recipe on a hybrid, a readout on a recipe.
    assert.equal(profile.category, composition.kind, `${composition.id} recipe category`);
  }
});

test("the build refuses a recipe name this module does not know", async () => {
  const build = await read("build.gradle.kts");
  assert.match(build, /Catalog compositions must name a known recipe/);
  for (const id of Object.keys(RECIPES)) {
    assert.ok(build.includes(`"${id}"`), `build.gradle.kts must list ${id}`);
  }
});

test("the recipes page explains every recipe under its own anchor and is reachable from the nav", async () => {
  const page = await read("site/recipes/index.html");
  for (const recipe of Object.values(RECIPES)) {
    assert.ok(page.includes(`id="${recipe.id}"`), `recipes page must anchor ${recipe.id}`);
    assert.ok(page.includes(recipe.name), `recipes page must name ${recipe.name}`);
  }
  for (const path of [
    "site/index.html",
    "site/model.html",
    "site/benchmarks/index.html",
    "site/apple/index.html",
    "site/intro/index.html",
    "site/contribute/index.html",
  ]) {
    assert.match(await read(path), /href="\/recipes\/"/, `${path} must link the recipes page`);
  }
});

test("the detail page renders a recipe section for compositions and nothing for plain models", () => {
  const section = renderRecipe({ kind: "recipe", recipe: "option-logit-readout" });
  assert.match(section, /<h2 id="recipe-title">Option-logit readout<\/h2>/);
  assert.match(section, /href="\/recipes\/#option-logit-readout"/);
  assert.equal(renderRecipe({ kind: undefined, architecture: "qwen3" }), "");
});
