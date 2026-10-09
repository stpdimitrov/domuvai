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
- Redirect addresses are this machine's only. A deployment adds its own https address in its realm; it does not
  widen these.
- Nobody registers themselves. An account is made by an administrator, and a login means nothing to the `api` until
  it is tied to a registered party (`identity_org.login`).

The `api`'s two settings for this realm:

    DOMUVAI_AUTH_ISSUER=https://<keycloak host>/realms/domuvai
    DOMUVAI_AUTH_AUDIENCE=domuvai-api

Importing it: `kc.sh start --import-realm` with the file in Keycloak's `data/import` directory.

Not yet run: the developer machine has no container runtime, so this file has been imported into no Keycloak. CI's
end-to-end job runs one from AUTH-03 on. To check on that first import: that the access token carries `sub` (newer
Keycloaks put it in the `basic` client scope, which the client must inherit) and the audience; and the redirect
addresses, which AUTH-03 narrows from `/*` to the one callback path.
