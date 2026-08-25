const SEEN_KEY = "modeljars-install-drawer-seen-v1";

export const INSTALL_COMMANDS = Object.freeze([
  Object.freeze({
    channel: "Homebrew",
    command: "brew install integrallis/tap/modeljars",
  }),
  Object.freeze({
    channel: "macOS or Linux",
    command:
      "curl -fsSL https://raw.githubusercontent.com/ModelJars/modeljars/main/install.sh | sh",
  }),
  Object.freeze({
    channel: "Scoop",
    command:
      "scoop bucket add integrallis https://github.com/integrallis/scoop-bucket\nscoop install modeljars",
  }),
]);

export async function copyInstallCommand(command, clipboard = navigator.clipboard) {
  if (!clipboard?.writeText) throw new Error("Clipboard access is unavailable");
  await clipboard.writeText(command);
  return command;
}

export function shouldAutoOpenInstallDrawer(storage = localStorage) {
  try {
    return storage.getItem(SEEN_KEY) !== "1";
  } catch {
    return false;
  }
}

function markInstallDrawerSeen(storage = localStorage) {
  try {
    storage.setItem(SEEN_KEY, "1");
  } catch {
    // A blocked storage API should not prevent installation instructions from working.
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function installDrawerMarkup() {
  return `
    <button
      id="install-cli-launcher"
      class="install-cli-launcher"
      type="button"
      aria-expanded="false"
      aria-controls="install-cli-drawer">
      Install CLI
    </button>
    <aside
      id="install-cli-drawer"
      class="install-cli-drawer"
      aria-labelledby="install-cli-title"
      aria-hidden="true">
      <div class="install-cli-heading">
        <div>
          <p class="eyebrow">Native CLI</p>
          <h2 id="install-cli-title">Install ModelJars</h2>
        </div>
        <button id="install-cli-close" class="install-cli-close" type="button" aria-label="Close installation instructions">&times;</button>
      </div>
      <p>Search, inspect, and pull verified GGUF and Safetensors model artifacts without installing Java.</p>
      <div class="install-cli-options">
        ${INSTALL_COMMANDS.map(
          ({ channel, command }, index) => `
            <section>
              <div>
                <h3>${escapeHtml(channel)}</h3>
                <button type="button" data-install-command="${index}" aria-label="Copy ${escapeHtml(channel)} installation command">Copy</button>
              </div>
              <pre><code>${escapeHtml(command)}</code></pre>
            </section>`,
        ).join("")}
      </div>
      <a class="install-cli-more" href="/#modeljars-cli">CLI usage and all installation options</a>
    </aside>`;
}

export function initializeInstallDrawer({
  documentObject = document,
  storage = localStorage,
  clipboard = navigator.clipboard,
} = {}) {
  if (documentObject.querySelector("#install-cli-drawer")) return;
  documentObject.body.insertAdjacentHTML("beforeend", installDrawerMarkup());

  const launcher = documentObject.querySelector("#install-cli-launcher");
  const drawer = documentObject.querySelector("#install-cli-drawer");
  const close = documentObject.querySelector("#install-cli-close");

  function setOpen(open) {
    drawer.classList.toggle("open", open);
    drawer.setAttribute("aria-hidden", String(!open));
    launcher.setAttribute("aria-expanded", String(open));
    launcher.classList.toggle("drawer-open", open);
    if (open) markInstallDrawerSeen(storage);
  }

  launcher.addEventListener("click", () => setOpen(!drawer.classList.contains("open")));
  close.addEventListener("click", () => {
    setOpen(false);
    launcher.focus();
  });
  documentObject.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && drawer.classList.contains("open")) {
      setOpen(false);
      launcher.focus();
    }
  });
  drawer.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-install-command]");
    if (!button) return;
    const command = INSTALL_COMMANDS[Number.parseInt(button.dataset.installCommand, 10)]?.command;
    if (!command) return;
    const original = button.textContent;
    try {
      await copyInstallCommand(command, clipboard);
      button.textContent = "Copied";
      button.classList.add("copied");
    } catch {
      button.textContent = "Copy failed";
    }
    setTimeout(() => {
      button.textContent = original;
      button.classList.remove("copied");
    }, 1_200);
  });

  if (shouldAutoOpenInstallDrawer(storage)) {
    requestAnimationFrame(() => setOpen(true));
  }
}

if (typeof document !== "undefined") initializeInstallDrawer();
