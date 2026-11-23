# Working notes: completing the portal in Java

Branch `docs/openspec-java`, started 2026-11-23 from `main` (`dca60a4`). One branch, one pull request; each task group is its own commit. These notes say what we are doing, what was decided and why, and what is left. The plan itself is in [openspec/changes/complete-java-portal](../openspec/changes/complete-java-portal/tasks.md) and [openspec/changes/react-hypermedia-ui](../openspec/changes/react-hypermedia-ui/tasks.md).

## What we are doing

The fork's `rust-rewrite` branch (Alex-Tsvetanov/unicloud-portal, `c16da1a`) rebuilt the portal in Rust, with a React app. It runs on the staging at `unicloud-portal-staging-tszc.onrender.com` (checked 2026-11-23: `/health` reports `c16da1a`). We take over its requirements (specs in `openspec/`, plain-language requirements in `docs/`) and build them in the Spring Boot portal. Where behaviour is visible from outside (the SSO contract, the directory, the staging tools, the React app's JSON API), Java copies the Rust behaviour.

## Decisions

| Date | Decision | Why |
|---|---|---|
| 2026-11-23 | Build in Java, keep Spring Authorization Server ([ADR 0001](adr/0001-keep-the-spring-boot-portal.md)) | The team owns the Java code; a maintained OIDC library instead of our own |
| 2026-11-23 | Keep MySQL in production and PostgreSQL on staging, keep the data | Modules keep their registrations; no new production database |
| 2026-11-23 | One cookie session instead of the portal's own JWT ([ADR 0003](adr/0003-one-cookie-session.md)) | One login per browser; disabled users and permission changes take effect at once; no token in browser storage. The JWT login stays until the app is switched over |
| 2026-11-23 | Add the 9 features the Rust code had beyond its specs | Sign-in throttling, hashing limit, token revocation, `typ: at+jwt`, reserved client ids, logout confirmation, security headers, API reference, start-up data |
| 2026-11-23 | `response_type` other than `code`: plain 400, no redirect (task 4.2b) | Fail closed; the library answers before the callback is checked |
| 2026-11-23 | API reference from the `feature/swagger` branch (PR #12) | `/api-docs` and `/openapi.json`, module-facing only |
| 2026-11-23 | UI: serve the React app's own JSON API exactly, and add HAL-FORMS links and forms ("option 3", [ADR 0004](adr/0004-react-ui-over-a-hal-forms-api.md)) | The app exists and a teammate owns it; the brief asks for hypermedia. The contract is pinned in `openspec/changes/react-hypermedia-ui/portal-api.openapi.json` |
| 2026-11-23 | Copy the fork's `web/` (the app and `@unicloud/ui`) into this repository unchanged | The task: "replacing the UI with React just like in the rust-rewrite"; whoever owns the UI carries on from there |
| 2026-11-23 | Port the Rust code behind the app as the reference: `portal_api/`, `accounts/`, `enrollment.rs`, `web/session.rs`, `web/csrf.rs`, `spa.rs`; its `tests/portal.rs` is the checklist | Same behaviour, same Bulgarian messages, same edge cases |

## Done

| Commit | What |
|---|---|
| `2db122d` | Specs and docs taken over from the fork, adapted to Java |
| `b63dedd` | Group 1: `DEAN`, permission bit 10, faculties and specialties (V5), the `faculty` claim, one dean per faculty |
| `9c1d959` | Group 4: discovery lists only what is served, unused endpoints 404, plain 400 for unknown modules, `openid` required, `typ: at+jwt` and `client_id` on access tokens, reserved client ids |
| `a200409` | Group 5: the directory API with `client_credentials` tokens |
| `15eb18d` | Task 4.2b decided: fail closed |
| `e4ab138` | 7.3 security headers; 7.4 the API reference |
| `0a98d88` | 7.1 request log; 7.2 clear error without the signing key; 7.5 start-up data; V6 `must_change_password` |
| `b7425a7` | The UI change rewritten for option 3 |
| `a1ec923` | These notes |
| `948a62c` | `web/` (the React app and `@unicloud/ui`) taken over from the fork |

## Doing now

Groups 2, 3 and 6 (backend only), ported from the Rust code:
1. The cookie session next to the JWT login: sign-in page, Argon2id with BCrypt upgrade, throttling, first-password change, logout, CSRF.
2. Account, catalogue and enrollment rules: who creates whom, teacher rejection, current semester, profile reads.
3. The app's JSON API (24 operations), with links and forms; hosting `/app` and `/ui/v1`.

### Step 1 done (2026-11-23)
- The cookie session (`PortalSessionSecurityConfig`, chain order 2) next to the old JWT chain (now order 3): `/login` and `/password` (Thymeleaf, texts from the Rust `accounts/handlers.rs`), `POST /logout`, CSRF with header `X-CSRF-Token` and field `csrf`, JSON errors for `/api/v1/portal`.
- `PortalUserFilter` in the portal and SSO chains: the user read per request, disabled users signed out, permissions current, `must_change_password` held back (with the waiting SSO login kept).
- Argon2id with BCrypt upgrade at sign-in, at most 4 hashes at once (`PortalPasswordEncoder`); throttling (`SignInThrottle`).
- The SSO chain sends a browser without a session to `/login?sso=1`.
- Tests: `SignInTests` (10), `SignInThrottleTests`, `PortalPasswordEncoderTests`; the full suite has 115 tests.

### Step 2 done (2026-11-23)
- The app's JSON API: all 24 operations of the pinned contract in `portalapi/` (session, profiles, catalogue and enrollment, teaching, dean, inspection, admin), ported from the Rust `portal_api/`, with its rules and Bulgarian messages. Errors `{error, message}`, never cached.
- `EnrollmentRules` (enroll with the contract's outcomes, cancel, teacher rejection, current semester), on the PR #14 locking; V7 adds `REJECTED` and `decided_by`. Enrollment acts on the signed-in student only.
- `AccountCreation`: inspectors create students, admins create staff, all with a first password to change; only an admin makes an admin.
- Tests: `PortalApiTests` (7, every role, every answer checked against the pinned contract by `PortalContract`), `PortalContractTests`; the full suite has 124 tests.

### Step 3 done (2026-11-23)
- `/app` and `/ui/v1` served (`AppHostingController`, ported from `spa.rs`); a browser at `/` goes to `/app/`. Docker builds `web/` in a Node stage; CI has a `web` job.
- HAL-FORMS `_links` and `_templates` on every answer of the app's API (`Hypermedia`), as in the UI design: navigation links on `/me`, forms for list actions on `/me`, `enroll`, `cancel`, `reject`, `setStatus`, `toggle`, `createCourse`, `createOffering`, each only while it would succeed (the enroll form uses the same rule as the handler, `EnrollmentRules.refusal`).
- Tests: `AppHostingTests`, and in `PortalApiTests` the links and forms per role and per state, and that every form targets an operation of the pinned contract; the full suite has 133 tests.

## Next

- Group 8: `docs/sso.md` ("How the portal does it"), README.
- After the switch-over: remove the JWT login and the old static pages (task 2.6), then V7-style clean-ups (task 8.4).

## Open questions and things others must do

- **Devops:** which Render service does the team's `deploy-staging` hook deploy to? The staging above runs the fork's Rust code. Also the `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` line added to `deploy.yml`.
- **UI owner (task 1.2 of the UI change):** agree to the pinned contract and the hypermedia on top, and decide where `web/` lives.
- **PostgreSQL:** V5, V6 and the directory SQL have run only on MySQL locally; CI's `test-postgres` is their first PostgreSQL run.
- **Production load test (task 7.6):** needs production.

## Checking it

- `./mvnw verify` with the local MySQL running (README, "Get set up"). CI runs the same on MySQL and PostgreSQL.
- Staging test user made on 2026-11-23 on the fork's staging: `staging-check-3a34ea4a@test.tu-sofia.bg` (a student, user 13). It stays until the free database is reset.
