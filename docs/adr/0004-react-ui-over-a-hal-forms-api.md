# React UI over a HAL-FORMS hypermedia API; reusable components for every team

Status: proposed (2026-11-23). Takes over the React part of the fork's ADR 0004 (2026-11-21) and keeps the brief's "hypermedia" wording, which the fork had dropped.

The portal's UI is React, so the same components can be reused by the other UniCloud modules that build with React. A React component can travel to another team's codebase; a server template cannot.

The backend delivers the UI as hypermedia. The app talks to `/api/v1/portal`, a HAL-FORMS API (`application/prs.hal-forms+json`, Spring HATEOAS) on the same site. Every answer carries:

- the data,
- `_links`: where the user can go from here,
- `_templates`: what the user can do here, as forms with method, target and fields with their rules.

A link or form is sent only when the user may use it now. The app knows one URL, the entry point. It finds every other URL by relation name, draws the navigation from the links and draws a button or a form only from a template. It never checks a role or a permission. The server checks every request again.

## The shape

- `web/packages/ui` is `@unicloud/ui` 1.0.0, taken over from the fork: presentational React components and the design tokens (CSS). Components take data as props and never call the portal, so another module can use them with its own data.
- `web/apps/portal` is the portal's single-page app (React 19, TypeScript, Vite, React Router, TanStack Query) with a small HAL-FORMS client. Spring serves it under `/app`.
- The app uses the session cookie ([ADR 0003](0003-one-cookie-session.md)) and sends the CSRF token in `X-CSRF-Token` on every change.
- Sign-in, password change, logout confirmation and error pages stay server-rendered (Thymeleaf). A module's login never waits for a JavaScript bundle. The staging page stays as it is.

## Distribution to other teams

The portal serves the packed library at `/ui/v1/unicloud-ui-<version>.tgz`. Teams download the file into their repository and run `npm install ./vendor/unicloud-ui-<version>.tgz`, so their builds never depend on the portal being awake. No npm registry or account is needed, and a version never changes once published. The same stylesheet is served at `/ui/v1/unicloud.css` for teams that do not use React (variant A of the [UI-sharing proposal](../proposals/ui-sharing.md)).

## Consequences

- There is now a JavaScript build: Node runs in a Docker build stage and in CI. The runtime is still one Spring Boot jar. `./mvnw verify` does not need Node.
- The first visit downloads the app bundle. It is cached afterwards; keep it small.
- Moving a button means changing a page in the app. Allowing or forbidding an action means changing the server only.
- The answers are larger than plain JSON, because they carry the links and forms.
