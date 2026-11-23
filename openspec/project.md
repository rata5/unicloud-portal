# Project context

## Purpose
UniCloud Portal is Team 1's product in the UniCloud course project at TU-Sofia (MCSE06). It is the university's single entry point: accounts, academic profiles, course offerings and enrollment. It is also the OpenID Connect provider that every other UniCloud module signs its users in through, and the directory those modules read who is who from.

Vocabulary: [GLOSSARY.md](../GLOSSARY.md). Decisions: [docs/adr](../docs/adr/). Product requirements: [docs/prd.md](../docs/prd.md).

## Tech stack
- Java 21, Spring Boot 4.1 (Spring Framework 7, Spring Security 7), one Maven module (`pom.xml`, `./mvnw`)
- Single sign-on: Spring Authorization Server (`spring-boot-starter-security-oauth2-authorization-server`), configured in `sso/config/SsoAuthorizationServerConfig.java`. We configure the library; we do not write our own OIDC provider ([ADR 0001](../docs/adr/0001-keep-the-spring-boot-portal.md))
- Sessions: Spring Session JDBC, one cookie `__Host-UNICLOUD_SSO` for the portal UI and single sign-on ([ADR 0003](../docs/adr/0003-one-cookie-session.md))
- Data: Spring Data JPA with Hibernate (`ddl-auto=validate`), Flyway migrations applied at start. Production runs MySQL 8.4 (`db/migration`), staging runs PostgreSQL 17 (`db/migration-postgresql`). Every migration exists in both, with the same version and name
- Portal API for the UI: Spring HATEOAS with HAL-FORMS (`application/prs.hal-forms+json`): every answer carries the links and forms the signed-in user may use ([ADR 0004](../docs/adr/0004-react-ui-over-a-hal-forms-api.md))
- UI: React 19, TypeScript, Vite, React Router and TanStack Query (`web/apps/portal`, served at `/app`). Shared components: `@unicloud/ui` (`web/packages/ui`, served at `/ui/v1/`), taken over from the fork's `rust-rewrite` branch. Server-rendered (Thymeleaf) only for sign-in, password change, logout confirmation and error pages. The staging page stays a static page with its own script
- Tests: JUnit 5 with Spring Boot Test and MockMvc against a real database (MySQL in the `test` job, PostgreSQL in `test-postgres`); Vitest with Testing Library for the frontend
- Docker (a Node stage for `web/`, a JDK stage that builds the jar and a jlink runtime, Alpine to run). Staging on Render. Production on Azure Container Apps

## Conventions
- UI text is Bulgarian. Code, API, logs and docs are English. Wire names use US spelling ("enrollment").
- Published names in `docs/sso.md` never change. The contract only grows.
- Every state-changing request through the portal UI has CSRF protection (`X-CSRF-Token` from the app, a form field on server pages). Every handler checks a permission, not a role.
- New code goes into feature packages (`academic`, `catalog`, `enrollment`, `directory`, `portalapi`, ...). Existing packages move only when a task rewrites them anyway.
- Docs follow the writing rules: short sentences, plain words, no em or en dashes.
- Conventional commits. One branch per change, merged to `main` by pull request (README, "Everyday workflow").

## Domain context
Roles: Student, Teacher, Dean, Inspector, Admin. A faculty has specialties, teachers, one dean and inspectors. A course offering is one course in one semester with one teacher and limited places. A student's enrollment request takes a free place at once.

## External dependencies
- Module teams 2 to 5 code against [docs/sso.md](../docs/sso.md).
- Render free web service and PostgreSQL (staging), Azure Container Apps and Azure Database for MySQL (production).
