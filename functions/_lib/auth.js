const encoder = new TextEncoder();
const decoder = new TextDecoder();

export const SESSION_COOKIE = "__Host-modeljars_session";
export const OAUTH_STATE_COOKIE = "__Host-modeljars_oauth_state";
export const OAUTH_PKCE_COOKIE = "__Host-modeljars_oauth_pkce";
export const OAUTH_RETURN_COOKIE = "__Host-modeljars_oauth_return";
export const SESSION_TTL_SECONDS = 8 * 60 * 60;
export const OAUTH_TTL_SECONDS = 10 * 60;

export const INVITED_GITHUB_USERS = Object.freeze([
  Object.freeze({ login: "bsbodden", id: 24109, type: "User" }),
]);

export function isInvitedGitHubUser(user) {
  if (!user || user.type !== "User" || !Number.isSafeInteger(Number(user.id))) return false;
  const login = String(user.login || "").toLowerCase();
  return INVITED_GITHUB_USERS.some(
    (invite) => invite.login === login && invite.id === Number(user.id),
  );
}

export function normalizeReturnTo(value) {
  if (
    typeof value !== "string" ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\") ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    return "/";
  }
  try {
    const parsed = new URL(value, "https://modeljars.invalid");
    if (parsed.origin !== "https://modeljars.invalid") return "/";
    const result = `${parsed.pathname}${parsed.search}${parsed.hash}`;
    if (
      parsed.pathname === "/login" ||
      parsed.pathname === "/login/" ||
      parsed.pathname === "/auth" ||
      parsed.pathname.startsWith("/auth/")
    ) {
      return "/";
    }
    return result;
  } catch {
    return "/";
  }
}

export function parseCookies(request) {
  const values = {};
  for (const part of (request.headers.get("Cookie") || "").split(";")) {
    const separator = part.indexOf("=");
    if (separator < 1) continue;
    const name = part.slice(0, separator).trim();
    const rawValue = part.slice(separator + 1).trim();
    try {
      values[name] = decodeURIComponent(rawValue);
    } catch {
      values[name] = rawValue;
    }
  }
  return values;
}

export function serializeCookie(
  name,
  value,
  { maxAge, sameSite = "Lax", httpOnly = true } = {},
) {
  if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name)) throw new Error("Invalid cookie name");
  const encoded = encodeURIComponent(String(value));
  const attributes = [`${name}=${encoded}`, "Path=/", "Secure", `SameSite=${sameSite}`];
  if (httpOnly) attributes.push("HttpOnly");
  if (Number.isFinite(maxAge)) attributes.push(`Max-Age=${Math.max(0, Math.floor(maxAge))}`);
  return attributes.join("; ");
}

export function clearCookie(name) {
  return serializeCookie(name, "", { maxAge: 0 });
}

export function appendCookie(headers, value) {
  headers.append("Set-Cookie", value);
}

export function oauthConfiguration(env) {
  const clientId = requiredText(env.GITHUB_OAUTH_CLIENT_ID, "GITHUB_OAUTH_CLIENT_ID");
  const clientSecret = requiredText(
    env.GITHUB_OAUTH_CLIENT_SECRET,
    "GITHUB_OAUTH_CLIENT_SECRET",
  );
  const sessionSecret = sessionSecretFrom(env);
  const candidate = requiredText(env.AUTH_BASE_URL, "AUTH_BASE_URL");
  const baseUrl = new URL(candidate);
  const local = baseUrl.hostname === "localhost" || baseUrl.hostname === "127.0.0.1";
  if ((!local && baseUrl.protocol !== "https:") || (local && !/^https?:$/.test(baseUrl.protocol))) {
    throw new Error("AUTH_BASE_URL must use HTTPS");
  }
  if (baseUrl.pathname !== "/" || baseUrl.search || baseUrl.hash) {
    throw new Error("AUTH_BASE_URL must be an origin");
  }
  return {
    clientId,
    clientSecret,
    sessionSecret,
    baseUrl: baseUrl.origin,
    callbackUrl: `${baseUrl.origin}/auth/callback`,
  };
}

export function sessionSecretFrom(env) {
  const secret = requiredText(env.AUTH_SESSION_SECRET, "AUTH_SESSION_SECRET");
  if (encoder.encode(secret).length < 32) {
    throw new Error("AUTH_SESSION_SECRET must contain at least 32 bytes");
  }
  return secret;
}

export async function createSession(user, secret, now = epochSeconds()) {
  if (!isInvitedGitHubUser(user)) throw new Error("GitHub user is not invited");
  const invite = INVITED_GITHUB_USERS.find((candidate) => candidate.id === Number(user.id));
  const payload = {
    id: invite.id,
    login: invite.login,
    expiresAt: now + SESSION_TTL_SECONDS,
  };
  const encodedPayload = bytesToBase64Url(encoder.encode(JSON.stringify(payload)));
  const signed = `v1.${encodedPayload}`;
  const signature = await sign(signed, secret);
  return `${signed}.${bytesToBase64Url(signature)}`;
}

export async function verifySession(value, secret, now = epochSeconds()) {
  try {
    const [version, encodedPayload, encodedSignature, extra] = String(value || "").split(".");
    if (version !== "v1" || !encodedPayload || !encodedSignature || extra !== undefined) return null;
    const signed = `${version}.${encodedPayload}`;
    const valid = await verify(signed, base64UrlToBytes(encodedSignature), secret);
    if (!valid) return null;
    const payload = JSON.parse(decoder.decode(base64UrlToBytes(encodedPayload)));
    if (
      !Number.isSafeInteger(payload.id) ||
      !Number.isSafeInteger(payload.expiresAt) ||
      payload.expiresAt <= now ||
      !isInvitedGitHubUser({ id: payload.id, login: payload.login, type: "User" })
    ) {
      return null;
    }
    return { login: payload.login, id: payload.id, expiresAt: payload.expiresAt };
  } catch {
    return null;
  }
}

export function randomBase64Url(size = 32) {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  return bytesToBase64Url(bytes);
}

export async function pkceChallenge(verifier) {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(verifier));
  return bytesToBase64Url(new Uint8Array(digest));
}

export function privateHeaders(headers = new Headers()) {
  headers.set("Cache-Control", "private, no-store");
  headers.set("Pragma", "no-cache");
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("X-Frame-Options", "DENY");
  headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
  headers.set("Referrer-Policy", "no-referrer");
  const vary = headers.get("Vary");
  if (!vary) headers.set("Vary", "Cookie");
  else if (!vary.toLowerCase().split(",").map((value) => value.trim()).includes("cookie")) {
    headers.set("Vary", `${vary}, Cookie`);
  }
  return headers;
}

export function redirect(location, status = 302, headers = new Headers()) {
  headers.set("Location", location);
  privateHeaders(headers);
  return new Response(null, { status, headers });
}

export function htmlResponse(html, status = 200, headers = new Headers()) {
  headers.set("Content-Type", "text/html; charset=utf-8");
  headers.set(
    "Content-Security-Policy",
    "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; " +
      "base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
  );
  privateHeaders(headers);
  return new Response(html, { status, headers });
}

export function clearOAuthCookies(headers) {
  for (const name of [OAUTH_STATE_COOKIE, OAUTH_PKCE_COOKIE, OAUTH_RETURN_COOKIE]) {
    appendCookie(headers, clearCookie(name));
  }
}

function requiredText(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} is not configured`);
  return value.trim();
}

async function hmacKey(secret, usages) {
  sessionSecretFrom({ AUTH_SESSION_SECRET: secret });
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    usages,
  );
}

async function sign(value, secret) {
  const key = await hmacKey(secret, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return new Uint8Array(signature);
}

async function verify(value, signature, secret) {
  const key = await hmacKey(secret, ["verify"]);
  return crypto.subtle.verify("HMAC", key, signature, encoder.encode(value));
}

function bytesToBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function base64UrlToBytes(value) {
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function epochSeconds() {
  return Math.floor(Date.now() / 1_000);
}
