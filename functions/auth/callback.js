import {
  OAUTH_PKCE_COOKIE,
  OAUTH_RETURN_COOKIE,
  OAUTH_STATE_COOKIE,
  SESSION_COOKIE,
  SESSION_TTL_SECONDS,
  appendCookie,
  clearOAuthCookies,
  createSession,
  htmlResponse,
  isInvitedGitHubUser,
  normalizeReturnTo,
  oauthConfiguration,
  parseCookies,
  redirect,
  serializeCookie,
} from "../_lib/auth.js";

export async function onRequestGet(context) {
  const requestUrl = new URL(context.request.url);
  const cookies = parseCookies(context.request);
  const state = requestUrl.searchParams.get("state");
  const code = requestUrl.searchParams.get("code");
  const headers = new Headers();
  clearOAuthCookies(headers);

  if (!state || !code || state !== cookies[OAUTH_STATE_COOKIE] || !cookies[OAUTH_PKCE_COOKIE]) {
    return htmlResponse(loginFailed(), 400, headers);
  }

  let configuration;
  try {
    configuration = oauthConfiguration(context.env);
    const token = await exchangeCode(
      configuration,
      code,
      cookies[OAUTH_PKCE_COOKIE],
    );
    const user = await authenticatedGitHubUser(token);
    if (!isInvitedGitHubUser(user)) return htmlResponse(notInvited(), 403, headers);

    const session = await createSession(user, configuration.sessionSecret);
    appendCookie(
      headers,
      serializeCookie(SESSION_COOKIE, session, {
        maxAge: SESSION_TTL_SECONDS,
        sameSite: "Strict",
      }),
    );
    return redirect(normalizeReturnTo(cookies[OAUTH_RETURN_COOKIE]), 302, headers);
  } catch {
    return htmlResponse(loginFailed(), 401, headers);
  }
}

async function exchangeCode(configuration, code, verifier) {
  const body = new URLSearchParams({
    client_id: configuration.clientId,
    client_secret: configuration.clientSecret,
    code,
    redirect_uri: configuration.callbackUrl,
    code_verifier: verifier,
  });
  const response = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });
  const payload = await response.json();
  if (!response.ok || typeof payload.access_token !== "string" || !payload.access_token) {
    throw new Error("GitHub token exchange failed");
  }
  return payload.access_token;
}

async function authenticatedGitHubUser(token) {
  const response = await fetch("https://api.github.com/user", {
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "User-Agent": "ModelJars-private-preview",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!response.ok) throw new Error("GitHub identity lookup failed");
  return response.json();
}

function loginFailed() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sign-in failed | ModelJars</title></head><body><main><h1>Sign-in failed</h1><p><a href="/login">Return to sign in</a></p></main></body></html>`;
}

function notInvited() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Access denied | ModelJars</title></head><body><main><h1>Access denied</h1><p>This private preview is invite only.</p><p><a href="/login">Use another account</a></p></main></body></html>`;
}
