import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { onRequest as protectSite } from "./_middleware.js";
import {
  INVITED_GITHUB_USERS,
  OAUTH_PKCE_COOKIE,
  OAUTH_RETURN_COOKIE,
  OAUTH_STATE_COOKIE,
  SESSION_COOKIE,
  SESSION_TTL_SECONDS,
  createSession,
  isInvitedGitHubUser,
  normalizeReturnTo,
  oauthConfiguration,
  verifySession,
} from "./_lib/auth.js";
import { onRequestGet as finishGitHubLogin } from "./auth/callback.js";
import { onRequestGet as startGitHubLogin } from "./auth/github.js";

const SESSION_SECRET = "test-only-session-secret-that-is-longer-than-thirty-two-characters";
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function environment(overrides = {}) {
  return {
    AUTH_BASE_URL: "https://modeljars.org",
    AUTH_SESSION_SECRET: SESSION_SECRET,
    GITHUB_OAUTH_CLIENT_ID: "github-client-id",
    GITHUB_OAUTH_CLIENT_SECRET: "github-client-secret",
    ...overrides,
  };
}

function oauthCookies() {
  return [
    `${OAUTH_STATE_COOKIE}=expected-state`,
    `${OAUTH_PKCE_COOKIE}=pkce-verifier`,
    `${OAUTH_RETURN_COOKIE}=${encodeURIComponent("/models/qwen3/")}`,
  ].join("; ");
}

test("contains only the bsbodden GitHub invite", () => {
  assert.deepEqual(INVITED_GITHUB_USERS, [
    { login: "bsbodden", id: 24109, type: "User" },
  ]);
  assert.equal(
    isInvitedGitHubUser({ login: "BSBodden", id: 24109, type: "User" }),
    true,
  );
  assert.equal(isInvitedGitHubUser({ login: "bsbodden", id: 99, type: "User" }), false);
  assert.equal(isInvitedGitHubUser({ login: "someone-else", id: 24109, type: "User" }), false);
  assert.equal(isInvitedGitHubUser({ login: "bsbodden", id: 24109, type: "Organization" }), false);
});

test("signs sessions and rejects tampering, expiration, and removed invites", async () => {
  const issuedAt = 1_700_000_000;
  const session = await createSession(
    { login: "bsbodden", id: 24109, type: "User" },
    SESSION_SECRET,
    issuedAt,
  );

  assert.deepEqual(await verifySession(session, SESSION_SECRET, issuedAt + 1), {
    login: "bsbodden",
    id: 24109,
    expiresAt: issuedAt + SESSION_TTL_SECONDS,
  });
  assert.equal(
    await verifySession(`${session.slice(0, -1)}x`, SESSION_SECRET, issuedAt + 1),
    null,
  );
  assert.equal(await verifySession(session, `${SESSION_SECRET}-wrong`, issuedAt + 1), null);
  assert.equal(
    await verifySession(session, SESSION_SECRET, issuedAt + SESSION_TTL_SECONDS),
    null,
  );
});

test("allows only same-origin return paths", () => {
  assert.equal(normalizeReturnTo("/models/qwen3/?tab=runtime"), "/models/qwen3/?tab=runtime");
  assert.equal(normalizeReturnTo("https://attacker.example/path"), "/");
  assert.equal(normalizeReturnTo("//attacker.example/path"), "/");
  assert.equal(normalizeReturnTo("/\\attacker.example"), "/");
  assert.equal(normalizeReturnTo("/login/?returnTo=%2Fcatalog.json"), "/");
  assert.equal(normalizeReturnTo("/auth"), "/");
  assert.equal(normalizeReturnTo("not-a-path"), "/");
});

test("requires an explicit OAuth origin", () => {
  const env = environment();
  delete env.AUTH_BASE_URL;

  assert.throws(
    () => oauthConfiguration(env),
    /AUTH_BASE_URL is not configured/,
  );
});

test("protects HTML and raw catalog routes while allowing only auth entrypoints", async () => {
  let nextCalls = 0;
  const next = async () => {
    nextCalls += 1;
    return new Response("private content", { headers: { "Cache-Control": "public" } });
  };

  const htmlResponse = await protectSite({
    request: new Request("https://modeljars.org/models/qwen3/?tab=runtime", {
      headers: { Accept: "text/html" },
    }),
    env: environment(),
    next,
  });
  assert.equal(htmlResponse.status, 302);
  assert.equal(
    htmlResponse.headers.get("location"),
    "/login?returnTo=%2Fmodels%2Fqwen3%2F%3Ftab%3Druntime",
  );

  const catalogResponse = await protectSite({
    request: new Request("https://modeljars.org/catalog.json"),
    env: environment(),
    next,
  });
  assert.equal(catalogResponse.status, 401);
  assert.equal(catalogResponse.headers.get("cache-control"), "private, no-store");
  assert.equal(nextCalls, 0);

  const loginResponse = await protectSite({
    request: new Request("https://modeljars.org/login"),
    env: environment(),
    next,
  });
  assert.equal(loginResponse.status, 200);
  assert.equal(nextCalls, 1);

  const trailingSlashLoginResponse = await protectSite({
    request: new Request("https://modeljars.org/login/"),
    env: environment(),
    next,
  });
  assert.equal(trailingSlashLoginResponse.status, 200);
  assert.equal(nextCalls, 2);
});

test("serves protected content only with a valid invited session", async () => {
  const now = Math.floor(Date.now() / 1_000);
  const session = await createSession(
    { login: "bsbodden", id: 24109, type: "User" },
    SESSION_SECRET,
    now,
  );
  const response = await protectSite({
    request: new Request("https://modeljars.org/catalog.json", {
      headers: { Cookie: `${SESSION_COOKIE}=${session}` },
    }),
    env: environment(),
    next: async () => new Response('{"models":[]}'),
  });

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "private, no-store");
  assert.equal(response.headers.get("x-robots-tag"), "noindex, nofollow, noarchive");
  assert.match(response.headers.get("vary"), /Cookie/i);
});

test("starts GitHub OAuth with state, PKCE, and no requested scopes", async () => {
  const response = await startGitHubLogin({
    request: new Request(
      "https://modeljars.org/auth/github?returnTo=%2Fmodels%2Fqwen3%2F",
    ),
    env: environment(),
  });
  const authorization = new URL(response.headers.get("location"));
  const cookies = response.headers.get("set-cookie");

  assert.equal(response.status, 302);
  assert.equal(authorization.origin, "https://github.com");
  assert.equal(authorization.pathname, "/login/oauth/authorize");
  assert.equal(authorization.searchParams.get("client_id"), "github-client-id");
  assert.equal(authorization.searchParams.get("redirect_uri"), "https://modeljars.org/auth/callback");
  assert.equal(authorization.searchParams.get("code_challenge_method"), "S256");
  assert.equal(authorization.searchParams.has("scope"), false);
  assert.match(cookies, new RegExp(`${OAUTH_STATE_COOKIE}=`));
  assert.match(cookies, new RegExp(`${OAUTH_PKCE_COOKIE}=`));
  assert.match(cookies, /HttpOnly/);
  assert.match(cookies, /Secure/);
});

test("creates a session only for the invited GitHub identity", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => {
    globalThis.fetch = originalFetch;
  });
  const requests = [];
  globalThis.fetch = async (request, options = {}) => {
    const url = String(request);
    requests.push({ url, options });
    if (url === "https://github.com/login/oauth/access_token") {
      return Response.json({ access_token: "temporary-token", token_type: "bearer" });
    }
    if (url === "https://api.github.com/user") {
      return Response.json({ login: "bsbodden", id: 24109, type: "User" });
    }
    throw new Error(`Unexpected request: ${url}`);
  };

  const response = await finishGitHubLogin({
    request: new Request(
      "https://modeljars.org/auth/callback?code=temporary-code&state=expected-state",
      { headers: { Cookie: oauthCookies() } },
    ),
    env: environment(),
  });
  const tokenBody = new URLSearchParams(requests[0].options.body);

  assert.equal(response.status, 302);
  assert.equal(response.headers.get("location"), "/models/qwen3/");
  assert.match(response.headers.get("set-cookie"), new RegExp(`${SESSION_COOKIE}=`));
  assert.equal(tokenBody.get("code_verifier"), "pkce-verifier");
  assert.equal(requests[1].options.headers.Authorization, "Bearer temporary-token");
});

test("denies an authenticated but uninvited GitHub identity", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => {
    globalThis.fetch = originalFetch;
  });
  globalThis.fetch = async (request) => {
    if (String(request) === "https://github.com/login/oauth/access_token") {
      return Response.json({ access_token: "temporary-token", token_type: "bearer" });
    }
    return Response.json({ login: "not-invited", id: 987654, type: "User" });
  };

  const response = await finishGitHubLogin({
    request: new Request(
      "https://modeljars.org/auth/callback?code=temporary-code&state=expected-state",
      { headers: { Cookie: oauthCookies() } },
    ),
    env: environment(),
  });

  assert.equal(response.status, 403);
  assert.doesNotMatch(response.headers.get("set-cookie") || "", new RegExp(`${SESSION_COOKIE}=[^.]+`));
  assert.doesNotMatch(await response.text(), /catalog|pure Java|model framework/i);
});

test("the GitHub Pages placeholder contains no framework usage details", async () => {
  const placeholder = await readFile(path.join(repositoryRoot, "site-public/index.html"), "utf8");
  assert.match(placeholder, /Private preview/);
  assert.doesNotMatch(placeholder, /catalog|pure Java|model framework|org\.modeljars/i);
  assert.doesNotMatch(placeholder, /href=["'][^"']*login/i);
});

test("deploys private content only through the authenticated Cloudflare site", async () => {
  const [pagesWorkflow, cloudflareWorkflow, build, routes, index, detail] = await Promise.all([
    readFile(path.join(repositoryRoot, ".github/workflows/pages.yml"), "utf8"),
    readFile(path.join(repositoryRoot, ".github/workflows/cloudflare-pages.yml"), "utf8"),
    readFile(path.join(repositoryRoot, "build.gradle.kts"), "utf8"),
    readFile(path.join(repositoryRoot, "site/_routes.json"), "utf8"),
    readFile(path.join(repositoryRoot, "site/index.html"), "utf8"),
    readFile(path.join(repositoryRoot, "site/model.html"), "utf8"),
  ]);

  assert.match(build, /generatePublicSite/);
  assert.match(pagesWorkflow, /generatePublicSite/);
  assert.match(pagesWorkflow, /build\/public-site/);
  assert.doesNotMatch(pagesWorkflow, /path: build\/site/);
  assert.match(cloudflareWorkflow, /functions\/\*\*/);
  assert.deepEqual(JSON.parse(routes), { version: 1, include: ["/*"], exclude: [] });
  assert.match(index, /action="\/auth\/logout" method="post"/);
  assert.match(detail, /action="\/auth\/logout" method="post"/);
});
