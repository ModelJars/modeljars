import { execFile } from "node:child_process";
import { access, readFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createServer } from "node:http";
import { mkdtemp } from "node:fs/promises";

const MIME_TYPES = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml"],
]);

async function browserExecutable() {
  const candidates = [
    process.env.CHROME_BIN,
    "/usr/bin/google-chrome-stable",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      await access(candidate);
      return candidate;
    } catch {
      // Try the next known browser location.
    }
  }
  throw new Error(`Chrome or Chromium is required for the rendered-site gate; checked ${candidates.join(", ")}`);
}

function serve(siteRoot) {
  const root = path.resolve(siteRoot);
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url || "/", "http://127.0.0.1");
      let pathname = decodeURIComponent(url.pathname);
      if (pathname.endsWith("/")) pathname += "index.html";
      const target = path.resolve(root, `.${pathname}`);
      if (target !== root && !target.startsWith(`${root}${path.sep}`)) {
        response.writeHead(403).end("Forbidden");
        return;
      }
      if (!(await stat(target)).isFile()) throw new Error("not a file");
      response.writeHead(200, {
        "Content-Type": MIME_TYPES.get(path.extname(target)) || "application/octet-stream",
        "Cache-Control": "no-store",
      });
      response.end(await readFile(target));
    } catch {
      response.writeHead(404).end("Not found");
    }
  });
}

function dumpDom(executable, profile, url) {
  const args = [
    "--headless=new",
    "--no-sandbox",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-background-networking",
    "--disable-component-update",
    "--disable-gpu",
    `--user-data-dir=${profile}`,
    "--virtual-time-budget=8000",
    "--dump-dom",
    url,
  ];
  return new Promise((resolve, reject) => {
    execFile(
      executable,
      args,
      { encoding: "utf8", maxBuffer: 20 * 1024 * 1024, timeout: 20_000 },
      (error, stdout, stderr) => {
        if (error && !stdout) {
          reject(new Error(`headless browser failed: ${error.message}\n${stderr.slice(-2000)}`));
          return;
        }
        resolve(stdout);
      },
    );
  });
}

export async function smokeGeneratedSite(siteRoot) {
  const root = path.resolve(siteRoot);
  const catalog = JSON.parse(await readFile(path.join(root, "catalog.json"), "utf8"));
  if (!Array.isArray(catalog) || !catalog.length) throw new Error("generated catalog must be non-empty");

  const server = serve(root);
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });

  try {
    const address = server.address();
    const profile = await mkdtemp(path.join(tmpdir(), "modeljars-site-smoke-"));
    const html = await dumpDom(
      await browserExecutable(),
      profile,
      `http://127.0.0.1:${address.port}/?site-smoke=1`,
    );
    const entries = html.match(/class="catalog-entry"/g)?.length || 0;
    if (html.includes("Catalog unavailable") || html.includes('class="error-state"')) {
      const error = html.match(/<p class="error-state">([\s\S]*?)<\/p>/)?.[1] || "unknown error";
      throw new Error(`rendered catalog reported an error: ${error}`);
    }
    if (entries !== catalog.length) {
      throw new Error(`rendered ${entries} catalog entries; expected ${catalog.length}`);
    }
    const label = `${catalog.length} qualified artifact${catalog.length === 1 ? "" : "s"}`;
    if (!html.includes(label)) throw new Error(`rendered catalog is missing result label: ${label}`);
    console.log(`Rendered-site gate passed with ${entries} qualified catalog entries`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  const siteRoot = process.argv[2];
  if (!siteRoot) throw new Error("usage: node tools/site-smoke.mjs <site-root>");
  await smokeGeneratedSite(siteRoot);
}
