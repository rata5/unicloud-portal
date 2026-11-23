# Tasks: the React app's JSON API, with hypermedia added

Same branch and pull request as `complete-java-portal`, where this is group 6. The backend tasks land together with group 2 of `complete-java-portal` (one cookie session). The JWT login and the old pages stay until task 4.3.

## 1. Contract
- [x] 1.1 Pin the contract: [portal-api.openapi.json](portal-api.openapi.json), the fork's "Portal UI" part at `c16da1a`
- [ ] 1.2 Agree with the UI owner: the pinned contract and the hypermedia on top (`web/` now lives in this repository, decided 2026-11-23)
- [ ] 1.3 ADR 0004 accepted

## 2. Backend
- [ ] 2.1 `spring-boot-starter-thymeleaf`; sign-in, password change, logout confirmation and error pages in Bulgarian, with the `csrf` form field
- [ ] 2.2 `portalapi`: every operation of the contract, with its fields and status codes (session, profiles, catalogue, enrollment, teaching, dean, inspection, admin)
- [ ] 2.3 `Hypermedia` helper; `_links` and `_templates` as in design.md, each from the same rule the handler enforces
- [ ] 2.4 JSON errors (`{error, message}`), JSON CSRF failures, `no-store` on module registration and errors
- [ ] 2.5 `/app` with the `index.html` fallback, `/ui/v1` with CORS, cache headers
- [ ] 2.6 Sign-in `next` return path; `/` sends signed-in browsers to `/app/`
- [ ] 2.7 `PortalApiContractTests` (JSON Schema validator, test scope), `PortalHypermediaTests`, permission and hosting tests

## 3. Frontend (UI owner)
- [x] 3.1 `web/` with the app and `@unicloud/ui` 1.0.0 in this repository (`948a62c`, from the fork at `c16da1a`)
- [ ] 3.2 The app runs against the Java portal unchanged
- [ ] 3.3 Move navigation, buttons and forms to the links and templates, page by page

## 4. Build and switch-over
- [ ] 4.1 Docker Node stage, CI `web` job
- [ ] 4.2 Checked in a browser against the Java portal: sign-in return, catalogue, enroll, cancel, sign-out, every staff and inspector form, module secret, status change, teacher reject, phone width
- [ ] 4.3 Remove the old static pages and the JWT login (complete-java-portal task 2.6)
- [ ] 4.4 Checked on staging after deploy: app, 401 JSON, package and stylesheet with CORS, missing asset 404
- [ ] 4.5 Ask the other teams which components they need (see `docs/proposals/ui-sharing.md`)
