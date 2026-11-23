# Change: React UI over a HAL-FORMS hypermedia API

## Why
The course brief asks for a hypermedia UI. Most UniCloud teams build their modules in Java, and some build React front ends. The portal's UI should be React, so its components can be reused by other teams ([docs/proposals/ui-sharing.md](../../../docs/proposals/ui-sharing.md)). And the backend should deliver the UI as hypermedia: each answer says what the user can see and do next, so the app holds no rules of its own ([ADR 0004](../../../docs/adr/0004-react-ui-over-a-hal-forms-api.md)).

The fork's `rust-rewrite` branch built a React app over plain JSON, and its ADR 0004 dropped the hypermedia wording on purpose. This change keeps React and keeps hypermedia.

## What changes
- **BREAKING (UI paths):** the portal's pages move under `/app` (for example `/courses.html` becomes `/app/courses`). `/` sends a signed-in browser to `/app/`. Modules do not link to these pages, so no module changes. The static pages in `src/main/resources/static` are removed.
- New HAL-FORMS API at `/api/v1/portal`, on the same site, with the session cookie and the CSRF token in the `X-CSRF-Token` header. Every answer carries `_links` (where the user can go) and `_templates` (what the user can do there). A link or form is present only when the user's permissions and the data allow it.
- New React app (`web/apps/portal`) served at `/app`. It knows one URL, the API root, and finds everything else by link relation. It draws a button or a form only when the answer has its template.
- `@unicloud/ui` 1.0.0 is taken over from the fork (`web/packages/ui`, commit `c16da1a`): presentational React components and the `uc-` stylesheet. The portal serves the package at `/ui/v1/unicloud-ui-1.0.0.tgz` and the stylesheet at `/ui/v1/unicloud.css`.
- Sign-in accepts `next=/app/...` and returns there after sign-in. Nothing outside `/app/` is accepted.
- Server-rendered with Thymeleaf: sign-in, password change, logout confirmation, error pages. The staging page stays as it is.
- Docker gains a Node build stage; CI gains a `web` job.

## Impact
- Specs: new `portal-ui`. `role-access`, `course-catalog`, `enrollment` and `academic-profile` scenarios that say "page" hold for the matching API resource and its links and forms (same permission, same Bulgarian message).
- Code: `portalapi/` (Spring HATEOAS), an `/app` and `/ui/v1` resource handler, `web/`.
- Dependencies: `spring-boot-starter-hateoas`, `spring-boot-starter-thymeleaf`. Node 22 and npm in the Docker build and in CI only.
- Docs: ADR 0004, README, `docs/proposals/ui-sharing.md`, `web/packages/ui/README.md`.
