import assert from "node:assert/strict";
import test from "node:test";

import {
  INSTALL_COMMANDS,
  copyInstallCommand,
  installDrawerMarkup,
  shouldAutoOpenInstallDrawer,
} from "./install-drawer.js";

test("offers copy-ready installation commands for every supported channel", () => {
  assert.deepEqual(
    INSTALL_COMMANDS.map(({ channel, command }) => [channel, command]),
    [
      ["Homebrew", "brew install integrallis/tap/modeljars"],
      [
        "macOS or Linux",
        "curl -fsSL https://raw.githubusercontent.com/ModelJars/modeljars/main/install.sh | sh",
      ],
      [
        "Scoop",
        "scoop bucket add integrallis https://github.com/integrallis/scoop-bucket\nscoop install modeljars",
      ],
    ],
  );
});

test("copies a selected installer without changing it", async () => {
  const copied = [];
  const command = INSTALL_COMMANDS[0].command;

  const result = await copyInstallCommand(command, {
    writeText: async (value) => copied.push(value),
  });

  assert.equal(result, command);
  assert.deepEqual(copied, [command]);
});

test("auto-opens only before this installation surface has been seen", () => {
  const empty = { getItem: () => null };
  const seen = { getItem: () => "1" };
  const unavailable = { getItem: () => { throw new Error("blocked"); } };

  assert.equal(shouldAutoOpenInstallDrawer(empty), true);
  assert.equal(shouldAutoOpenInstallDrawer(seen), false);
  assert.equal(shouldAutoOpenInstallDrawer(unavailable), false);
});

test("renders a non-modal, accessible drawer with one copy control per channel", () => {
  const markup = installDrawerMarkup();

  assert.match(markup, /id="install-cli-drawer"/);
  assert.match(markup, /aria-labelledby="install-cli-title"/);
  assert.match(markup, /id="install-cli-close"/);
  assert.equal((markup.match(/data-install-command=/g) || []).length, 3);
  assert.doesNotMatch(markup, /role="dialog"|aria-modal/);
});
