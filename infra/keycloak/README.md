# The sign-in realm

`domuvai-realm.json` describes the Keycloak realm the `api` trusts (ADR-011): the realm `domuvai`, and the one
client this repository adds to it — `domuvai-web`, the Next.js server. Keycloak adds clients of its own to every
realm (its account and admin consoles); their tokens do not carry the `api`'s audience, so the `api` refuses them.

What the file fixes for `domuvai-web`, and a test holds the file to (`RealmFileTest`):

- The web signs in by the authorization-code flow with PKCE (S256) and nothing else: no password grant, no implicit
  flow, no service account.
- The client is confidential and the file carries **no secret**. Keycloak makes one when the realm is imported; it is
  read from the admin console and handed to the web as a setting, never committed.
- A mapper puts the audience `domuvai-api` in every access token. The `api` refuses a token without it, so this is
  the value of `DOMUVAI_AUTH_AUDIENCE`.
- A sign-in comes back to one address only — `/auth/callback` on this machine's web, with no wildcard. A deployment
  adds its own https callback in its realm; it does not widen these.
- A refresh token is not spent by its first use (`revokeRefreshToken` off). The web renews a session in its
  middleware, and a page with its prefetches renews more than once at the same moment; with refresh tokens spent on
  first use all but one renewal would be refused and the person signed out.
- Nobody registers themselves. An account is made by an administrator, and a login means nothing to the `api` until
  it is tied to a registered party (`identity_org.login`).

The `api`'s two settings for this realm:

    DOMUVAI_AUTH_ISSUER=https://<keycloak host>/realms/domuvai
    DOMUVAI_AUTH_AUDIENCE=domuvai-api

Importing it: `kc.sh start --import-realm` with the file in Keycloak's `data/import` directory.

The web's settings for this realm are in `web/README.md` (Sign-in); its client secret is read from the admin console
(Clients → `domuvai-web` → Credentials) and handed to the web as `DOMUVAI_AUTH_CLIENT_SECRET`.

**It runs in CI.** The end-to-end job (`.github/workflows/e2e.yml`) starts a Keycloak that imports this file as it is,
makes a client secret, a person and a password for that run only, and `tools/check_e2e.py` signs in through
Keycloak's own form, reads every screen signed in from an `api` that is closed, and signs out. What that first import
settled: the access token carries `sub` and the audience `domuvai-api` — the `api` accepts the web's bearer and
`GET /api/identity/me` names Keycloak's own id for the person. The developer machine has no container runtime, so
the realm still runs nowhere but there.
