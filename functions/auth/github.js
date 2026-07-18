import {
  OAUTH_PKCE_COOKIE,
  OAUTH_RETURN_COOKIE,
  OAUTH_STATE_COOKIE,
  OAUTH_TTL_SECONDS,
  appendCookie,
  htmlResponse,
  normalizeReturnTo,
  oauthConfiguration,
  pkceChallenge,
  randomBase64Url,
  redirect,
  serializeCookie,
} from "../_lib/auth.js";

export async function onRequestGet(context) {
  let configuration;
  try {
    configuration = oauthConfiguration(context.env);
  } catch {
    return htmlResponse(configurationUnavailable(), 503);
  }

  const requestUrl = new URL(context.request.url);
  const returnTo = normalizeReturnTo(requestUrl.searchParams.get("returnTo"));
  const state = randomBase64Url(32);
  const verifier = randomBase64Url(48);
  const challenge = await pkceChallenge(verifier);
  const authorization = new URL("https://github.com/login/oauth/authorize");
  authorization.searchParams.set("client_id", configuration.clientId);
  authorization.searchParams.set("redirect_uri", configuration.callbackUrl);
  authorization.searchParams.set("state", state);
  authorization.searchParams.set("code_challenge", challenge);
  authorization.searchParams.set("code_challenge_method", "S256");
  authorization.searchParams.set("allow_signup", "false");
  authorization.searchParams.set("prompt", "select_account");

  const headers = new Headers();
  appendCookie(headers, serializeCookie(OAUTH_STATE_COOKIE, state, { maxAge: OAUTH_TTL_SECONDS }));
  appendCookie(
    headers,
    serializeCookie(OAUTH_PKCE_COOKIE, verifier, { maxAge: OAUTH_TTL_SECONDS }),
  );
  appendCookie(
    headers,
    serializeCookie(OAUTH_RETURN_COOKIE, returnTo, { maxAge: OAUTH_TTL_SECONDS }),
  );
  return redirect(authorization.toString(), 302, headers);
}

function configurationUnavailable() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Preview unavailable | ModelJars</title></head><body><main><h1>Private preview unavailable</h1><p>Authentication is not configured.</p></main></body></html>`;
}
