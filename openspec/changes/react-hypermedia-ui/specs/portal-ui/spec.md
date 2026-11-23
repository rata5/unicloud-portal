## ADDED Requirements

### Requirement: The portal app
The portal SHALL serve its user interface as a single-page app at `/app`. Any path under `/app/` that is not a built file SHALL return the app's `index.html`. A missing file under `/app/assets/` SHALL return 404.

#### Scenario: Deep link
- **WHEN** a browser opens `/app/courses`
- **THEN** the portal returns the app's `index.html` with `Cache-Control: no-cache`

#### Scenario: Missing asset
- **WHEN** a browser asks for `/app/assets/no-such-file.js`
- **THEN** the portal answers 404 and does not mark the answer as cacheable for a year

#### Scenario: Signed-in root
- **WHEN** a signed-in browser opens `/`
- **THEN** the portal redirects to `/app/`

### Requirement: The pinned JSON contract
The portal SHALL serve every operation of [portal-api.openapi.json](../../portal-api.openapi.json) under `/api/v1/portal`, with the same paths, methods, request bodies, response fields and status codes, as `application/json`. Fields and paths of the contract MUST NOT be renamed, moved or removed, so the app on staging works against the Java portal without a change. New fields and new operations MAY be added.

#### Scenario: The staging app signs in on Java
- **WHEN** the app built on the fork (commit `c16da1a`) is served by the Java portal and a student signs in
- **THEN** its home, profile, catalogue and enrollment pages show the student's data
- **AND** enrolling and cancelling work

#### Scenario: Same shape
- **WHEN** a student calls `GET /api/v1/portal/courses`
- **THEN** each item has at least `id`, `code`, `title`, `ects`, `faculty`, `teacher`, `enrolled`, `capacity` and `my_status`, as in the contract

### Requirement: Hypermedia in every answer
Every answer of `/api/v1/portal` SHALL also carry HAL-FORMS controls: `_links` (each with `href`) for where the user can go, and `_templates` (each with `method`, `target` and `properties`) for what the user can do there. An object answer carries them at its top level. In a list answer, each item carries its own, and actions on the whole list are templates of `GET /api/v1/portal/me`. A link or template MUST appear only when the signed-in user may use it at that moment: the user's permissions, the user's own data (for example the student's status) and the resource's state (for example free places) decide. The server MUST check the same rules again when the link or template is used.

#### Scenario: Entry point for a student
- **WHEN** a signed-in student calls `GET /api/v1/portal/me`
- **THEN** `_links` has `self`, `profile`, `courses` and `enrollments`, matching `nav`
- **AND** there are no links or templates for staff, module, student or course management

#### Scenario: Entry point for a dean
- **WHEN** a signed-in dean calls `GET /api/v1/portal/me`
- **THEN** `_links` has `faculty`

#### Scenario: Enroll template only when possible
- **WHEN** an active student reads the catalogue and an offering is open, of the current semester, has a free place, and the student holds no enrollment in it
- **THEN** that item has an `enroll` template with method `POST` and target `/api/v1/portal/courses/{id}/enroll`
- **AND** the same item read by an interrupted student, or when it is full or closed, has no `enroll` template

#### Scenario: Form fields from the server
- **WHEN** an inspector calls `GET /api/v1/portal/me`
- **THEN** its `createStudent` template lists the fields of the contract's `NewStudent` with their rules (required, length, range), and the specialty field lists the specialties as options

#### Scenario: Using a template without the right
- **WHEN** a student sends the request of a `createCourse` template that the student was never given
- **THEN** the portal answers 403 and changes nothing

### Requirement: The app can follow the server
Every action of the contract SHALL have a template wherever it is allowed, and every page of the app SHALL be reachable through a link from `GET /api/v1/portal/me`, so that the app can take its navigation, its buttons and its form fields from the server alone.

#### Scenario: Every action has its template
- **WHEN** a user of each role reads every resource the contract offers that role
- **THEN** each action the contract allows that user at that moment appears as a template, with the contract's method and path

### Requirement: Session, CSRF and errors
Every call SHALL use the session cookie. Every call that changes data SHALL carry the session's CSRF token in the `X-CSRF-Token` header; server-rendered forms, and the app's logout form, send it as the form field `csrf`. Every error SHALL be JSON `{error, message}` with a Bulgarian message.

#### Scenario: No session
- **WHEN** a call to `/api/v1/portal/me` has no session
- **THEN** the portal answers 401 with `error` = `unauthenticated` and does not redirect

#### Scenario: First password not changed
- **WHEN** a user who must change the first password calls the API
- **THEN** the portal answers 403 with `error` = `password_change_required`

#### Scenario: Change after the session ended
- **WHEN** a browser whose session has ended posts an enrollment
- **THEN** the portal answers 401 with `error` = `unauthenticated`

#### Scenario: Missing CSRF header
- **WHEN** a signed-in student posts an enrollment without the right `X-CSRF-Token`
- **THEN** the portal answers 403 with `error` = `forbidden` and nothing changes

#### Scenario: Logout from the app
- **WHEN** the app posts its logout form to `/logout` with the field `csrf`
- **THEN** the session ends and the browser goes to the sign-in page

#### Scenario: Missing permission
- **WHEN** a student calls `/api/v1/portal/admin/staff`
- **THEN** the portal answers 403 with `error` = `forbidden`

#### Scenario: Refused enrollment
- **WHEN** a student enrolls in an offering they are already enrolled in
- **THEN** the portal answers 409 with the contract's `EnrollResult`, `outcome` = `ALREADY_ENROLLED` and a Bulgarian message

#### Scenario: Module secret
- **WHEN** an admin registers a module through the API
- **THEN** the answer carries the secret once and `Cache-Control: no-store`

### Requirement: Return after sign-in
Sign-in SHALL accept a `next` path and return there after sign-in, only if the path starts with `/app/`, has no `//` and no backslash, and is at most 512 characters. Otherwise sign-in SHALL go to `/app/`. A waiting single sign-on login SHALL take priority.

#### Scenario: Return to the asked page
- **WHEN** a user signs in from `/login?next=/app/courses`
- **THEN** the portal redirects to `/app/courses`

#### Scenario: Another site
- **WHEN** a user signs in with `next` = `//evil.example/app/`
- **THEN** the portal redirects to `/app/`

### Requirement: Shared UI package
The portal SHALL serve `@unicloud/ui` as an npm tarball at `/ui/v1/unicloud-ui-<version>.tgz` and its stylesheet at `/ui/v1/unicloud.css`, readable from any origin. The package's components SHALL take data as props and SHALL NOT call the portal. A published version SHALL NOT change. Version 1.0.0 SHALL be the fork's 1.0.0, so a team that installed it from staging gets the same package.

#### Scenario: Another team's page loads the stylesheet
- **WHEN** a page on another origin requests `/ui/v1/unicloud.css`
- **THEN** the answer carries `Access-Control-Allow-Origin: *`

#### Scenario: Another team installs the package
- **WHEN** a team downloads `/ui/v1/unicloud-ui-1.0.0.tgz` and runs `npm install` on the file
- **THEN** they get the components, their TypeScript types and the stylesheet
