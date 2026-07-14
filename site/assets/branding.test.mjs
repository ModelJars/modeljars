import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const iconsDirectory = path.join(repositoryRoot, "media/icons");

test("ships the shared ModelJars icon set", async () => {
  const requiredIcons = [
    "android-chrome-192x192.png",
    "android-chrome-512x512.png",
    "apple-touch-icon.png",
    "favicon-16x16.png",
    "favicon-32x32.png",
    "favicon.ico",
    "site.webmanifest",
  ];

  await Promise.all(requiredIcons.map((icon) => access(path.join(iconsDirectory, icon))));
});

test("uses the shared logo in repository and website branding", async () => {
  const [readme, index, modelDetail, manifestSource] = await Promise.all([
    readFile(path.join(repositoryRoot, "README.md"), "utf8"),
    readFile(path.join(repositoryRoot, "site/index.html"), "utf8"),
    readFile(path.join(repositoryRoot, "site/model.html"), "utf8"),
    readFile(path.join(iconsDirectory, "site.webmanifest"), "utf8"),
  ]);
  const manifest = JSON.parse(manifestSource);

  assert.match(readme, /media\/icons\/android-chrome-512x512\.png/);
  assert.match(index, /src="\/android-chrome-192x192\.png"/);
  assert.match(index, /id="theme-toggle"/);
  assert.match(index, /id="domain-filters"/);
  assert.match(index, /id="advanced-filters"/);
  assert.match(modelDetail, /id="model-detail"/);
  assert.match(modelDetail, /assets\/model-detail\.js/);
  assert.match(index, /rel="manifest" href="\/site\.webmanifest"/);
  assert.equal(manifest.name, "ModelJars");
  assert.equal(manifest.short_name, "ModelJars");
});
