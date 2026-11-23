# Change: the React app's JSON API, with hypermedia added

## Why
The portal's UI is a React app, so its components can be reused by other teams ([docs/proposals/ui-sharing.md](../../../docs/proposals/ui-sharing.md)). That app already exists: it was built on the fork's `rust-rewrite` branch and runs on staging today, against a plain JSON API at `/api/v1/portal`. A teammate owns the UI and carries the app on. So the Java portal must serve the API that app speaks.

The course brief also asks for hypermedia: the backend should deliver the UI, so each answer says what the user can see and do next. The fork dropped that on purpose (its ADR 0004). We keep the app's API exactly and add hypermedia on top of it ([ADR 0004](../../../docs/adr/0004-react-ui-over-a-hal-forms-api.md)).

## What changes
- **The contract is pinned.** [portal-api.openapi.json](portal-api.openapi.json) is the "Portal UI" part of the fork's `api/openapi.json` (commit `c16da1a`), unchanged: 24 operations under `/api/v1/portal`, their fields and their status codes. The Java portal serves exactly that. Paths and fields are never renamed or removed; new ones may be added.
- **Hypermedia is added, not swapped in.** Every answer also carries HAL-FORMS `_links` (where the user can go) and `_templates` (what the user can do now, with method, target and fields). Object answers carry them at the top; in list answers each item carries its own. A link or form appears only when the user may use it at that moment. The app on staging ignores fields it does not know, so it keeps working unchanged; the UI owner can move its buttons and forms to the templates step by step. `nav` in `/me` already works this way.
- **BREAKING (UI paths):** the portal's pages move under `/app` (for example `/courses.html` becomes `/app/courses`). `/` sends a signed-in browser to `/app/`. Modules do not link to these pages. The static pages in `src/main/resources/static` are removed once the app is served.
- Server-rendered with Thymeleaf, owned by the backend: sign-in (`/login`, with a `next` return path), the first-password change (`/password`), the logout confirmation and error pages. The staging page stays as it is.
- The portal serves the built app at `/app`, and `@unicloud/ui` at `/ui/v1/unicloud-ui-1.0.0.tgz` and `/ui/v1/unicloud.css`.

## Who builds what
- **Backend (Java):** the API of the pinned contract with its hypermedia, the session and CSRF, the server-rendered pages, hosting `/app` and `/ui/v1`, the Docker stage that puts the built files in the image.
- **UI owner:** the React app and `@unicloud/ui` (`web/`), its tests, and moving the app to the templates.

## Impact
- Specs: new `portal-ui`. `role-access`, `course-catalog`, `enrollment` and `academic-profile` scenarios that say "page" hold for the matching API call and its links and forms (same permission, same Bulgarian message).
- Code: `portalapi/` (controllers and answers of the pinned contract, plus a small HAL-FORMS helper), an `/app` and `/ui/v1` resource handler, Thymeleaf templates.
- Dependencies: `spring-boot-starter-thymeleaf`. Node in the Docker build and in CI only.
- Docs: ADR 0004, README, `docs/proposals/ui-sharing.md`, `web/packages/ui/README.md`.
