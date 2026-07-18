import {
  SESSION_COOKIE,
  normalizeReturnTo,
  oauthConfiguration,
  parseCookies,
  redirect,
  verifySession,
  htmlResponse,
} from "./_lib/auth.js";

export async function onRequestGet(context) {
  const requestUrl = new URL(context.request.url);
  const returnTo = normalizeReturnTo(requestUrl.searchParams.get("returnTo"));
  let configuration;
  try {
    configuration = oauthConfiguration(context.env);
  } catch {
    return htmlResponse(renderLogin(returnTo, false), 503);
  }

  const session = await verifySession(
    parseCookies(context.request)[SESSION_COOKIE],
    configuration.sessionSecret,
  );
  if (session) return redirect(returnTo);
  return htmlResponse(renderLogin(returnTo, true));
}

function renderLogin(returnTo, available) {
  const target = `/auth/github?returnTo=${encodeURIComponent(returnTo)}`;
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="robots" content="noindex,nofollow,noarchive">
    <title>Private preview | ModelJars</title>
    <link rel="icon" href="/favicon.ico">
    <style>
      :root { color-scheme: light dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; }
      * { box-sizing: border-box; }
      body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f4f5f7; color: #17191d; }
      main { width: min(100% - 32px, 420px); border: 1px solid #d8dbe1; background: #fff; padding: 32px; border-radius: 8px; box-shadow: 0 18px 45px rgba(19, 25, 36, .10); }
      .brand { display: flex; align-items: center; gap: 10px; color: inherit; font-size: 18px; font-weight: 700; }
      .brand img { width: 36px; height: 36px; }
      .eyebrow { margin: 36px 0 8px; color: #626875; font-size: 12px; font-weight: 700; text-transform: uppercase; }
      h1 { margin: 0; font-size: 28px; letter-spacing: 0; }
      p { color: #626875; line-height: 1.55; }
      a.button { display: flex; justify-content: center; width: 100%; margin-top: 24px; padding: 12px 16px; border-radius: 6px; background: #17191d; color: #fff; font-weight: 700; text-decoration: none; }
      .unavailable { padding: 12px; border-left: 3px solid #b42318; background: #fff4f2; color: #8a1c13; }
      @media (prefers-color-scheme: dark) {
        body { background: #111318; color: #f4f5f7; }
        main { background: #1a1d23; border-color: #343944; box-shadow: none; }
        .eyebrow, p { color: #a8afbc; }
        a.button { background: #f4f5f7; color: #17191d; }
        .unavailable { background: #321b1b; color: #ffb4ab; }
      }
    </style>
  </head>
  <body>
    <main>
      <div class="brand"><img src="/android-chrome-192x192.png" alt=""><span>ModelJars</span></div>
      <p class="eyebrow">Private preview</p>
      <h1>Sign in</h1>
      <p>Access is currently limited to invited GitHub accounts.</p>
      ${available ? `<a class="button" href="${target}">Continue with GitHub</a>` : `<p class="unavailable">Sign-in is temporarily unavailable.</p>`}
    </main>
  </body>
</html>`;
}
