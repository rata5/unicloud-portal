# Change: Complete the week 1 portal in Java

## Why
The week 1 brief asks for a live portal with accounts, academic profiles, a catalogue with enrollment, role boundaries, survival of the 08:00 rush and a written uptime promise. From week 2 the portal must also be the single source of truth for the other teams. The Java portal on `main` already has the single sign-on provider, the staging tools and enrollment without overselling. It lacks the rest. The other teams' interface sheet also needs data the current contract cannot give: deans, faculties and lookups with no user present ([ADR 0002](../../../docs/adr/0002-grow-the-sso-contract.md)).

The requirements were first written for a Rust rewrite on the fork's `rust-rewrite` branch. We keep those requirements and build them in the Spring Boot portal instead ([ADR 0001](../../../docs/adr/0001-keep-the-spring-boot-portal.md)).

## What changes
- **BREAKING (internal only):** the portal's own JWT API (`/api/v1/auth/login`, `/api/v1/auth/sso-session`, `/api/v1/auth/me`, Bearer tokens with `aud=unicloud-api`) and the Bearer route `POST /api/v1/sso/clients` are removed. One cookie session replaces them ([ADR 0003](../../../docs/adr/0003-one-cookie-session.md)). No module uses them.
- **BREAKING (internal only):** the old REST routes for the static pages (`/api/v1/courses`, `/api/v1/offerings`, `/api/v1/enrollments`, `/api/v1/students`, `/api/v1/users/me`, `/api/v1/auth/register`) are replaced by the HAL-FORMS portal API of the `react-hypermedia-ui` change. The static pages in `src/main/resources/static` go with them.
- Added to the contract: role `DEAN`, permission `STUDENT_PROFILE_READ_FACULTY` (bit 10), the `faculty` claim, the `client_credentials` grant with the `directory` scope, and a read-only directory API.
- Faculties and specialties become tables. Teachers, deans and inspectors belong to one faculty. A student's faculty comes from the specialty.
- Enrollment: a request takes a free place at once, as today. The teacher may reject it, which frees the place.
- Accounts are created by inspectors (students) and admins (staff), with a first password the user must change.
- Admins register modules on a portal page instead of a Bearer API.
- Spring Authorization Server's defaults are narrowed to what `docs/sso.md` promises: discovery, errors and token claims.
- `docs/slo.md` states the uptime promise.

## What stays
- Spring Authorization Server, its tables and every registered module. Modules do **not** register again.
- MySQL in production and PostgreSQL on staging, with Flyway migrations for both. Data is kept: no fresh database.
- The signing key pair and its variables (`APP_JWT_PRIVATE_KEY`, `APP_JWT_PUBLIC_KEY`), the issuer, the callback rules, the staging tools' routes and shapes.
- Enrollment locking from PR #14 (one code path for both databases).

## Impact
- Specs (all new): `accounts-and-sign-in`, `academic-profile`, `course-catalog`, `enrollment`, `role-access`, `sso-provider`, `module-directory`, `staging-tools`, `operations`. The UI is in the separate change `react-hypermedia-ui`.
- Code: `auth/`, `sso/`, `staging/`, `user/`, `student/`, the old `controller/`, `service/`, `entity/` and `repository/` packages, new feature packages, Flyway V5 and later in both migration folders.
- Docs: `docs/sso.md` (additions with each task, then "How the portal does it" and "Not done yet"), `README.md`, `.github/workflows/deploy.yml`, `Dockerfile`.
- Module teams: treat unknown roles as no access (tell them before `DEAN` ships). Optionally adopt the directory API.
