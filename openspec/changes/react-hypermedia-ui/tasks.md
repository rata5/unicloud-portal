# Tasks: React UI over HAL-FORMS

Same branch and pull request as `complete-java-portal`, where this is group 6. Groups 1 and 2 land together with group 2 of `complete-java-portal` (one cookie session), so there is always a working sign-in.

## 1. Contract
- [ ] 1.1 Spike: confirm that Spring HATEOAS in Boot 4.1 renders `_templates` on embedded collection items; pick the fallback in design.md if not
- [ ] 1.2 ADR 0004 accepted
- [ ] 1.3 The entry point, the relation names and the template names as in design.md

## 2. Server
- [ ] 2.1 `spring-boot-starter-hateoas`, `spring-boot-starter-thymeleaf`
- [ ] 2.2 Thymeleaf sign-in, password change, logout confirmation and error pages, in Bulgarian
- [ ] 2.3 `portalapi`: entry point, profile, catalogue and offering, enrollments, teaching and roster, student profile, faculty, inspection (students, courses, offerings), staff, modules
- [ ] 2.4 Links and templates only when allowed; the same rule checked again in every handler
- [ ] 2.5 JSON errors, JSON CSRF failures, `no-store` on module registration and errors
- [ ] 2.6 `/app` with the `index.html` fallback, `/ui/v1` with CORS, cache headers, CSP
- [ ] 2.7 Sign-in `next` return path; `/` sends signed-in browsers to `/app/`
- [ ] 2.8 Remove `src/main/resources/static` (the old pages) and the REST routes they used

## 3. Frontend
- [ ] 3.1 Copy `@unicloud/ui` 1.0.0 from the fork (commit `c16da1a`) with its tests and README; note the source
- [ ] 3.2 HAL-FORMS client and `<HalForm>` in `web/apps/portal`
- [ ] 3.3 The pages: profile, catalogue with faculty filter and search in the URL, enrollments, teaching and roster, student profile, faculty, inspection, staff, modules (secret shown once)
- [ ] 3.4 Vitest tests: navigation from links, buttons from templates, 401 and `password_change_required`

## 4. Build and checks
- [ ] 4.1 `PortalApiShapeTests`: every role through the entry point, links and templates per role and per state
- [ ] 4.2 Permission tests: every template's request without the permission answers 403
- [ ] 4.3 Docker Node stage, CI `web` job
- [ ] 4.4 Checked in a browser on a local server: sign-in return, catalogue, enroll, cancel, sign-out, every staff and inspector form, module secret, status change, teacher reject, phone width
- [ ] 4.5 Checked on staging after deploy: app, 401 JSON, package and stylesheet with CORS, missing asset 404
- [ ] 4.6 Ask the other teams which components they need (see `docs/proposals/ui-sharing.md`)
