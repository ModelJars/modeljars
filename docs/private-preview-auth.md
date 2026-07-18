# ModelJars Private Preview Authentication

ModelJars.org is temporarily an invite-only private preview. Cloudflare Pages serves the catalog
behind GitHub OAuth; GitHub Pages serves only a metadata-free placeholder because a static host
cannot enforce user authentication.

The sole invite is the immutable GitHub identity `bsbodden` (user ID `24109`). Both the login and
ID must match. The ID prevents a future owner of a renamed login from inheriting access, while the
login check makes invite changes explicit.

## Request boundary

The root Pages middleware in `functions/_middleware.js` runs for every path, including generated
HTML, JavaScript, images, and `catalog.json`. Its public allowlist contains only the login and OAuth
endpoints plus the icons needed by the login page.

An authenticated session is an HMAC-SHA256 signed, `HttpOnly`, `Secure`, `SameSite=Strict` cookie
with an eight-hour lifetime. Every request rechecks the signed GitHub ID and login against the
current invite list. Responses are marked `private, no-store` and `noindex`. GitHub OAuth uses the
authorization-code flow with PKCE and a state cookie, and requests no optional GitHub scopes.

Missing authentication configuration fails closed with HTTP 503. An anonymous HTML request is
redirected to `/login`; an anonymous data or asset request receives HTTP 401.

## Production configuration

Create a GitHub OAuth App with these values:

```text
Homepage URL:              https://modeljars.org
Authorization callback:   https://modeljars.org/auth/callback
```

GitHub OAuth Apps currently have one callback URL. Use a separate app for local development, and
do not treat arbitrary Cloudflare preview URLs as authenticated environments.

In the Cloudflare Pages `modeljars` project, configure the production environment with:

| Binding | Storage | Value |
|---|---|---|
| `GITHUB_OAUTH_CLIENT_ID` | Variable | OAuth App client ID |
| `GITHUB_OAUTH_CLIENT_SECRET` | Encrypted secret | OAuth App client secret |
| `AUTH_SESSION_SECRET` | Encrypted secret | At least 32 random bytes |
| `AUTH_BASE_URL` | Variable | `https://modeljars.org` |

Generate the session secret with `openssl rand -base64 48`. These are Cloudflare Pages bindings,
not GitHub Actions repository secrets. The deployment workflow separately requires the existing
`CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` GitHub secrets.

Redeploy after adding or rotating bindings. After the first gated deployment, purge any cached
objects from the former public deployment before validating anonymous access.

## Local verification

Create `.dev.vars` from `.dev.vars.example` and use a separate local GitHub OAuth App with this
callback:

```text
http://localhost:8788/auth/callback
```

Then run:

```bash
npm ci
./gradlew generateSite
npm run site:dev
```

The automated checks do not require live GitHub credentials:

```bash
npm test
./gradlew test generateSite generatePublicSite
```

With Wrangler running, verify the unauthenticated boundary:

```bash
curl -i -H 'Accept: text/html' http://localhost:8788/
curl -i http://localhost:8788/catalog.json
curl -i http://localhost:8788/login
```

Expected results are a redirect to `/login`, HTTP 401 JSON, and HTTP 200 respectively. Complete a
browser login with `bsbodden`, verify catalog and model detail routes, then submit the `Sign out`
form. A different GitHub identity must receive HTTP 403 and no session cookie.

## Invite maintenance

Invites live in `functions/_lib/auth.js` and are locked by `functions/auth.test.mjs`. Resolve a
candidate's immutable ID from `https://api.github.com/users/<login>`, add both ID and normalized
login, and change the exact-allowlist test in the same pull request. Access changes take effect on
deployment; existing sessions are rejected as soon as their identity is removed from the list.

## Deployment containment

`generateSite` produces `build/site`, the private Cloudflare application. It includes
`site/_routes.json`, which forces every route through Pages Functions. `generatePublicSite`
produces `build/public-site`, which contains only the private-preview placeholder and brand icons.
The GitHub Pages workflow is intentionally restricted to that second output.

The gate protects the deployed website. It does not make source files or Git history private; the
repository remains open source by design.

Official references:

- <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps>
- <https://developers.cloudflare.com/pages/functions/>
- <https://developers.cloudflare.com/pages/functions/middleware/>
- <https://developers.cloudflare.com/pages/functions/bindings/>
