# Tasks

Everything is on one branch and one pull request into `main`. Each numbered group is its own commit (or a few), self-contained, so the review can go group by group. `[x]` means `main` (commit `dca60a4`) already does it.

## 0. Before any code
- [x] 0.1 Commit these specs and docs (the first commit)
- [x] 0.2 Tell module teams in `docs/sso.md` that `role` and `permissions` may gain values, that an unknown value means no access, and that `DEAN` is coming. Tell them before the pull request reaches production

## Already on main
- [x] Spring Authorization Server: authorize with PKCE, token, `/userinfo`, `/connect/logout`, JWKS with the RFC 7638 `kid`
- [x] Callback policy `allowlist` / `any`, host list file and `APP_SSO_ALLOWED_CALLBACK_HOSTS`, checked at every authorize and logout
- [x] Codes valid 60 seconds, single use across replicas, replay revokes the first exchange's tokens
- [x] Hourly deletion of logins older than a day
- [x] SSO session cookie `__Host-UNICLOUD_SSO`, 30 minutes idle, 8 hours maximum (`SsoSessionMaxAgeFilter`)
- [x] Staging tools: page and API, on only with the `staging` profile
- [x] Enrollment never oversells, on MySQL and PostgreSQL (`EnrollmentConcurrencyTests`); cancel and re-enroll reuse the record
- [x] `/health` without the database, `/` with the replica, `X-Served-By`, graceful shutdown (20 s)
- [x] Flyway at start for MySQL and PostgreSQL; pool size set by configuration

## 1. Roles, DEAN and faculties
- [x] 1.1 `Permission.STUDENT_PROFILE_READ_FACULTY` at bit 10; `Role.DEAN` with its defaults; enum order `ADMIN, INSPECTOR, TEACHER, DEAN, STUDENT`
- [x] 1.2 V5 in both folders: `faculties`, `specialties` (seeded), `users.faculty_code`, `DEAN` in the role check, one dean per faculty, `students.specialty_id` filled from the text, `courses.faculty_code`
- [x] 1.3 JPA entities and repositories for faculties and specialties; a user's faculty (own, through the specialty, or none)
- [x] 1.4 `faculty` claim in the `unicloud` scope (`SsoUserClaims`), in the ID token and `/userinfo`
- [x] 1.5 Staging tools: `faculty` on create and in the users list, `DEAN`, 11 permissions, `specialty` by name or code, Bulgarian refusal of a second dean
- [x] 1.6 Tests on both databases: migration, dean uniqueness, claims, staging shapes (`DeanAndFacultyTests`; run locally on MySQL, PostgreSQL in CI)
- [x] 1.7 `docs/sso.md`: `DEAN`, `faculty`, the roles table, "eleven permissions"

## 2. One cookie session
- [x] 2.1 Chain 3 with the session, form login at `/login` (Thymeleaf, Bulgarian), CSRF (`X-CSRF-Token` header, `_csrf` form field), `POST /logout`
- [x] 2.2 Chain 1's entry point to `/login?sso=1`; after sign-in, continue the saved `/oauth2/authorize`
- [x] 2.3 `PortalUser` loaded per request; disabled users anonymous at once
- [x] 2.4 `DelegatingPasswordEncoder` with Argon2id; BCrypt hashes verify and are upgraded at sign-in; same timing for unknown emails
- [x] 2.5 `must_change_password` (column added by V6 in group 7) and `/password`; held back for pages, the API and `/oauth2/authorize`
- [x] 2.6 Remove the portal JWT: `/api/v1/auth/login`, `/api/v1/auth/me`, `/api/v1/auth/sso-session`, `POST /api/v1/sso/clients`, the `unicloud-api` resource server. Old routes answer 404. Only once the app is served and checked against the Java portal (react-hypermedia-ui task 4.3); until then the JWT login and the cookie session work side by side
- [x] 2.7 Staging routes answer 404 without the profile
- [x] 2.8 `SsoFlowTests` and the other suites sign in through `/login`; new session, CSRF and first-password tests (`SignInTests`, `SignInThrottleTests`, `PortalPasswordEncoderTests`; `SsoFlowTests` and `SsoAnyCallbackTests` sign in at `/login` and register modules through the admin API; `PublicRoutesTests` checks the old routes answer 404)
- [x] 2.9 Lands together with group 6's sign-in page and app shell, so there is always a working sign-in
- [x] 2.10 Sign-in throttling: 10 failures in 15 minutes lock the email for 15 minutes
- [x] 2.11 At most 4 password hashes at once per replica
- [x] 2.12 A password change revokes the user's module logins; the new password must differ
- [x] 2.13 Sign-out revokes the module logins made through that session

## 3. Accounts, catalogue and enrollment rules
- [x] 3.1 Inspector creates students (`STUDENT_CREATE`), admin creates staff (`STAFF_CREATE`), only an admin creates an admin; first password set by the creator; Bulgarian messages for duplicates
- [x] 3.2 Student status change (`STUDENT_CREATE`), effective for enrollment and new tokens
- [ ] 3.3 Courses with a faculty, offerings with a teacher of that faculty; permission checks; no hard deletes; capacity not below the enrolled count (done through the app's API except a capacity change, which the contract has no operation for yet)
- [x] 3.4 Enrollment for the signed-in student only, current semester only (`app.current-semester`)
- [x] 3.5 Teacher rejection (V7: `REJECTED`, `decided_by`); a rejected student cannot enroll again
- [x] 3.6 Profile reads: ANY, FACULTY, ENROLLED, else 404
- [x] 3.7 Tests: accounts, profile access, enrollment rules (`PortalApiTests`), a mixed race (`EnrollmentRulesRaceTests`)

## 4. SSO provider alignment
- [x] 4.1 Discovery lists only what is served; revocation, introspection and device endpoints switched off
- [x] 4.2 Plain 400 page for an unknown client, a refused callback and a bad `response_type` without a trusted callback
- [x] 4.2b `response_type` other than `code`: a plain 400 with no redirect, even with a registered callback (decided 2026-11-23: fail closed; the spec says so now)
- [x] 4.3 `invalid_request` when `openid` is missing from `scope`
- [x] 4.4 `client_id` in the access token
- [x] 4.5 `DiscoveryTests` and the new error cases in `SsoFlowTests`
- [x] 4.6 `docs/sso-wire-contract.md`: mark the decided items
- [x] 4.7 `typ: at+jwt` on access tokens; no `typ` on ID tokens
- [x] 4.8 Reserved module ids: every `unicloud-*` id and ids made only of digits
- [x] 4.9 `/connect/logout` without a valid `id_token_hint` asks for a confirmation with a CSRF-protected form (needs group 2's session, CSRF and Thymeleaf; done with it)

## 5. Directory
- [x] 5.1 `client_credentials` with the `directory` scope; validator for `invalid_scope`; `aud=unicloud-directory`; `unicloud-directory` reserved
- [x] 5.2 Chain 2 and the six GET routes, paging with `Link`
- [x] 5.3 The `directory` flag in module registration (the admin route and staging now; the portal page comes with group 6)
- [x] 5.4 `DirectoryTests`
- [x] 5.5 `docs/sso.md`: the directory section, the troubleshooting rows, the production checklist line

## 6. The React app's JSON API, with hypermedia added
See [react-hypermedia-ui/tasks.md](../react-hypermedia-ui/tasks.md).

## 7. Operations
- [x] 7.1 Structured JSON request logs with route group, status, duration and replica, and no secrets (Spring Boot structured logging, ECS; on with the staging profile and in production through `LOGGING_STRUCTURED_FORMAT_CONSOLE`)
- [x] 7.2 Start-up refuses to run without the signing key, naming the missing variable
- [x] 7.3 Security headers on every response (CSP, `nosniff`, `X-Frame-Options`, `Referrer-Policy`)
- [x] 7.4 `/openapi.json` and Swagger UI at `/api-docs` (springdoc, as in the reverted PR #12), plus the SSO endpoints the library serves
- [x] 7.5 Start-up data: the first admin from `APP_BOOTSTRAP_ADMIN_*` when no admin can sign in; demo data with `APP_DEMO_PASSWORD` only with the `staging` or `dev` profile. V6 adds `must_change_password`; the first admin has it set, and group 2 makes the sign-in honour it
- [ ] 7.6 Spike test (`loadtest/`) against production, results in `docs/slo.md`

## 8. Docs and release
- [x] 8.1 `docs/sso.md`: "How the portal does it" and "Not done yet" for the new design
- [x] 8.2 README: sign-in, the app, the new variables, the Node stage
- [ ] 8.3 Tell module teams what changed. They do not register again
- [ ] 8.4 In the release after this one: `students.specialty_id` and `courses.faculty_code` NOT NULL, drop `students.specialty` (V5 kept them nullable for replicas that predate it)
