import {
  SESSION_COOKIE,
  clearCookie,
  appendCookie,
  parseCookies,
  privateHeaders,
  redirect,
  sessionSecretFrom,
  verifySession,
} from "./_lib/auth.js";

const PUBLIC_PATHS = new Set([
  "/login",
  "/auth/github",
  "/auth/callback",
  "/auth/logout",
  "/favicon.ico",
  "/favicon-16x16.png",
  "/favicon-32x32.png",
  "/apple-touch-icon.png",
  "/android-chrome-192x192.png",
]);

export async function onRequest(context) {
  const url = new URL(context.request.url);
  const publicPath =
    url.pathname.length > 1 && url.pathname.endsWith("/")
      ? url.pathname.slice(0, -1)
      : url.pathname;
  if (PUBLIC_PATHS.has(publicPath)) return context.next();

  let secret;
  try {
    secret = sessionSecretFrom(context.env);
  } catch {
    return unavailable();
  }

  const cookies = parseCookies(context.request);
  const session = await verifySession(cookies[SESSION_COOKIE], secret);
  if (!session) return unauthenticated(context.request, url);

  context.data ||= {};
  context.data.githubUser = session;
  const response = await context.next();
  const headers = privateHeaders(new Headers(response.headers));
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

function unauthenticated(request, url) {
  const headers = new Headers();
  if ((request.headers.get("Accept") || "").includes("text/html")) {
    const returnTo = `${url.pathname}${url.search}${url.hash}`;
    appendCookie(headers, clearCookie(SESSION_COOKIE));
    return redirect(`/login?returnTo=${encodeURIComponent(returnTo)}`, 302, headers);
  }
  headers.set("Content-Type", "application/json; charset=utf-8");
  appendCookie(headers, clearCookie(SESSION_COOKIE));
  privateHeaders(headers);
  return new Response(JSON.stringify({ error: "authentication_required" }), {
    status: 401,
    headers,
  });
}

function unavailable() {
  const headers = privateHeaders(new Headers({ "Content-Type": "text/plain; charset=utf-8" }));
  return new Response("Private preview unavailable", { status: 503, headers });
}
