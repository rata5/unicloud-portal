# One cookie session for the portal UI and SSO

Status: proposed (2026-11-23). Taken over from the fork's ADR 0003 (2026-11-20), adapted to the Java portal.

The Java portal has two logins: a Bearer JWT kept in each tab's `sessionStorage` for the portal's own API, and a `__Host-UNICLOUD_SSO` cookie for single sign-on, bridged by `POST /api/v1/auth/sso-session`. The new UI is a React app on the portal's own site (`/app`), so it can use the session cookie directly. No browser code needs a token. So the portal keeps only the cookie session, stored by Spring Session JDBC in the database (MySQL in production, PostgreSQL on staging) so every replica sees it. `/api/v1/auth/login`, `/api/v1/auth/me`, `/api/v1/auth/sso-session`, `POST /api/v1/sso/clients` and the portal's own JWT (`aud=unicloud-api`) are removed.

Modules are not affected: sections 1 to 10 of `docs/sso.md` never mention these routes. Signing out of the portal still ends the SSO session. The old note that "losing the portal token in one tab does not end the SSO session" no longer applies, because there is no per-tab token.

Requests that change state need CSRF protection. A Bearer header used to give that protection for free, and a cookie does not. The app sends the token in `X-CSRF-Token`; server pages send it as a form field.

The session is read on every portal API call. The stateless API never did that. The spike test must show that the database keeps up at 08:00.
