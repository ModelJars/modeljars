import { spawn } from "node:child_process";

// Wait for the application, not Chrome's --dump-dom lifecycle/virtual clock. The latter can
// return an initial document when the browser is killed, before its modules have rendered.
export async function renderedCatalog(executable, profile, url, timeoutMillis = 30_000) {
  const browser = spawn(executable, [
    "--headless=new", "--no-sandbox", "--no-first-run", "--no-default-browser-check",
    "--disable-background-networking", "--disable-component-update", "--disable-gpu",
    `--user-data-dir=${profile}`, "--remote-debugging-pipe", "about:blank",
  ], { stdio: ["ignore", "ignore", "pipe", "pipe", "pipe"] });
  const pending = new Map();
  const diagnostics = [];
  let nextId = 0;
  let buffer = "";
  let stderr = "";
  let failure;
  const fail = (error) => {
    failure ||= error;
    for (const { reject } of pending.values()) reject(failure);
    pending.clear();
  };
  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    if (failure) return reject(failure);
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    browser.stdio[3].write(`${JSON.stringify({ id, method, params, sessionId })}\0`);
  });
  browser.on("error", fail);
  browser.on("exit", (code, signal) => fail(new Error(`headless browser exited: ${signal || code}`)));
  browser.stderr.on("data", (data) => { stderr = (stderr + data).slice(-4000); });
  browser.stdio[3].on("error", fail);
  browser.stdio[4].setEncoding("utf8");
  browser.stdio[4].on("data", (data) => {
    buffer += data;
    let boundary;
    while ((boundary = buffer.indexOf("\0")) !== -1) {
      const message = JSON.parse(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 1);
      const request = pending.get(message.id);
      if (request) {
        pending.delete(message.id);
        if (message.error) request.reject(new Error(message.error.message));
        else request.resolve(message.result);
      } else if (message.method === "Runtime.exceptionThrown") {
        const detail = message.params.exceptionDetails;
        fail(new Error(`browser JavaScript error: ${detail.exception?.description || detail.text}`));
      } else if (message.method === "Network.loadingFailed") {
        diagnostics.push(`request failed: ${message.params.errorText}`);
      } else if (message.method === "Network.responseReceived" && message.params.response.status >= 400) {
        diagnostics.push(`HTTP ${message.params.response.status}: ${message.params.response.url}`);
      }
    }
  });
  const timer = setTimeout(() => fail(new Error(
    `rendered catalog did not become ready within ${timeoutMillis}ms`,
  )), timeoutMillis);
  try {
    const { targetId } = await send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
    await send("Runtime.enable", {}, sessionId);
    await send("Network.enable", {}, sessionId);
    await send("Page.enable", {}, sessionId);
    const navigation = await send("Page.navigate", { url }, sessionId);
    if (navigation.errorText) throw new Error(`navigation failed: ${navigation.errorText}`);
    // Navigation may acknowledge before the new execution context exists. Polling also leaves
    // startup/module/fetch failures subject to the same real-time deadline as rendering.
    while (true) {
      const result = await send("Runtime.evaluate", {
        expression: `document.querySelector('#catalog-results')?.getAttribute('aria-busy') === 'false'
          ? { html: document.documentElement.outerHTML, entries: document.querySelectorAll('#catalog-results .catalog-entry').length } : null`,
        returnByValue: true,
      }, sessionId);
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
      if (result.result.value) return result.result.value;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  } catch (error) {
    throw new Error(`${error.message}\n${diagnostics.join("\n")}\n${stderr}`.trim(), { cause: error });
  } finally {
    clearTimeout(timer);
    browser.kill("SIGKILL");
  }
}
