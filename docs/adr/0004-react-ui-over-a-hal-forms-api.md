# React UI over the app's JSON API, with hypermedia added; reusable components for every team

Status: proposed (2026-11-23). Takes over the React part of the fork's ADR 0004 (2026-11-21), and keeps the brief's "hypermedia" wording, which the fork had dropped.

The portal's UI is React, so the same components can be reused by the other UniCloud modules that build with React. A React component can travel to another team's codebase; a server template cannot.

The app exists already. It was built on the fork, runs on staging, and a teammate carries it on. It speaks a plain JSON API at `/api/v1/portal`. The Java portal serves that API exactly: its paths, fields and status codes are pinned in `openspec/changes/react-hypermedia-ui/portal-api.openapi.json`, and only ever grow.

The backend also delivers the UI as hypermedia, on top of that API. Every answer keeps its fields and gains HAL-FORMS controls:

- `_links`: where the user can go from here,
- `_templates`: what the user can do here, as forms with method, target and fields with their rules.

A link or form is sent only when the user may use it now. Object answers carry them at the top, list items each carry their own, and actions on a whole list are templates of `GET /api/v1/portal/me`. The server checks every request again.

## Why both

- **The app keeps working.** It ignores fields it does not know, so the Java portal can replace the Rust one under it without a release of the app.
- **Hypermedia without a rewrite.** The UI owner moves buttons and forms to the templates page by page. Once a page draws from them, the rule that allows an action lives only on the server. `nav` in `GET /me` already works that way.
- **Rejected:** HAL-FORMS alone (different paths and shapes) would have meant rewriting a working app. Plain JSON alone would drop the brief's hypermedia.

## The shape

- `web/packages/ui` is `@unicloud/ui` 1.0.0, from the fork: presentational React components and the design tokens (CSS). Components take data as props and never call the portal.
- `web/apps/portal` is the app (React 19, TypeScript, Vite, React Router, TanStack Query). Spring serves it under `/app`.
- The app uses the session cookie ([ADR 0003](0003-one-cookie-session.md)) and sends the CSRF token in `X-CSRF-Token` on every change.
- Sign-in, password change, logout confirmation and error pages stay server-rendered (Thymeleaf), owned by the backend. A module's login never waits for a JavaScript bundle.

## Distribution to other teams

The portal serves the packed library at `/ui/v1/unicloud-ui-<version>.tgz`. Teams download the file into their repository and run `npm install ./vendor/unicloud-ui-<version>.tgz`, so their builds never depend on the portal being awake. No npm registry or account is needed, and a version never changes once published. The same stylesheet is served at `/ui/v1/unicloud.css` for teams that do not use React (variant A of the [UI-sharing proposal](../proposals/ui-sharing.md)).

## Consequences

- There is now a JavaScript build: Node runs in a Docker build stage and in CI. The runtime is still one Spring Boot jar. `./mvnw verify` does not need Node.
- The answers are a little larger, because they carry the links and forms.
- A change to the contract is agreed with the UI owner first, and written into the pinned file.
