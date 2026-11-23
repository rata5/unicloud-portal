# Keep the Spring Boot portal and build the week 1 requirements in it

Status: proposed (2026-11-23)

The fork's `rust-rewrite` branch rebuilt the portal in Rust, with a hand-built OpenID Connect provider (its ADR 0001). We keep its requirements (the specs in `openspec/` and `docs/prd.md`) and build them in the existing Spring Boot portal instead of replacing it.

- The Java portal already meets a large part of them: the SSO provider, single-use codes across replicas, the callback rules, the staging tools, enrollment without overselling on both databases, health and graceful shutdown. Each is pinned by tests.
- The OIDC provider stays Spring Authorization Server, a maintained library. We configure it and narrow its defaults to what `docs/sso.md` promises. We do not own the protocol code.

## Consequences

- The Rust code on the fork is not merged. Its specs and docs are taken over and adapted (this change, `openspec/changes/complete-java-portal/`).
- Modules keep their registrations. There is no fresh database and no data move: Flyway adds to the existing tables, on MySQL (production) and PostgreSQL (staging).
- Where the Spring default differs from `docs/sso.md` (discovery lists more than is served; an unknown client gets 401 with no body instead of a 400 page), we change the configuration to match the guide. `docs/sso-wire-contract.md` lists each case.
- The staging at `unicloud-portal-staging-tszc.onrender.com` runs the fork's Rust portal (on 2026-11-23 its `/health` reports `c16da1a`, the head of `rust-rewrite`), deployed by the fork's `render.yaml`. Before the Java changes reach staging, the team decides which code that service deploys. Until then the Java docs do not name that address.
