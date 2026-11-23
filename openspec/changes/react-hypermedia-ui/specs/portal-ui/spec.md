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

### Requirement: Hypermedia API
The portal SHALL offer its app a HAL-FORMS API (`application/prs.hal-forms+json`) with one entry point, `GET /api/v1/portal`. Every resource SHALL carry a `self` link. Navigation SHALL be links (`_links`) and every action SHALL be a form (`_templates`) with its method, target and fields. A link or form MUST appear only when the signed-in user may use it at that moment: the user's permissions, the user's own data (for example the student's status) and the resource's state (for example free places) decide. The server MUST check the same rules again when the link or form is used.

#### Scenario: Entry point for a student
- **WHEN** a signed-in student calls `GET /api/v1/portal`
- **THEN** the answer has the user's name and role, the CSRF token, and links to the profile, the catalogue and the student's enrollments
- **AND** it has no links to staff, module, student or course management

#### Scenario: Entry point for a dean
- **WHEN** a signed-in dean calls `GET /api/v1/portal`
- **THEN** the answer has a link to the dean's faculty (its offerings and students)

#### Scenario: Enroll form only when possible
- **WHEN** an active student reads an open offering of the current semester with a free place, in which the student is not enrolled
- **THEN** the offering has an `enroll` template
- **AND** the same offering read by an interrupted student, or when it is full or closed, has no `enroll` template

#### Scenario: Form fields from the server
- **WHEN** an inspector reads the students resource
- **THEN** its `create` template lists the fields with their rules (required, length, range) and the specialty field lists the specialties as options

#### Scenario: Using a form without the right
- **WHEN** a student sends the request of a course `create` template that the student was never given
- **THEN** the portal answers 403 and changes nothing

### Requirement: The app follows the server
The app SHALL know only the URL of the entry point. It MUST find every other URL in links and templates, by relation name, and MUST NOT build API URLs itself. It SHALL show a navigation item only for a link the entry point gives, and a button or a form only for a template the resource gives. It MUST NOT check roles or permissions itself.

#### Scenario: Navigation from links
- **WHEN** the entry point for a teacher has links to the profile, the catalogue and the teacher's offerings
- **THEN** the app's navigation shows exactly those three items

#### Scenario: Button from a template
- **WHEN** an offering has no `enroll` template
- **THEN** the app shows no "Запиши се" button for it

### Requirement: Session, CSRF and errors
Every call SHALL use the session cookie. Every call that changes data SHALL carry the session's CSRF token in the `X-CSRF-Token` header. Every error SHALL be JSON `{error, message}` with a Bulgarian message.

#### Scenario: No session
- **WHEN** a call to `/api/v1/portal` has no session
- **THEN** the portal answers 401 with `error` = `unauthenticated` and does not redirect

#### Scenario: First password not changed
- **WHEN** a user who must change the first password calls the API
- **THEN** the portal answers 403 with `error` = `password_change_required`

#### Scenario: Change after the session ended
- **WHEN** a browser whose session has ended sends an enrollment
- **THEN** the portal answers 401 with `error` = `unauthenticated`

#### Scenario: Missing CSRF header
- **WHEN** a signed-in student sends an enrollment without the right `X-CSRF-Token`
- **THEN** the portal answers 403 with `error` = `forbidden` and nothing changes

#### Scenario: Missing permission
- **WHEN** a student calls `/api/v1/portal/admin/staff`
- **THEN** the portal answers 403 with `error` = `forbidden`

#### Scenario: Refused enrollment
- **WHEN** a student enrolls in an offering they are already enrolled in
- **THEN** the portal answers 409 with `outcome` = `ALREADY_ENROLLED` and a Bulgarian message

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
The portal SHALL serve `@unicloud/ui` as an npm tarball at `/ui/v1/unicloud-ui-<version>.tgz` and its stylesheet at `/ui/v1/unicloud.css`, readable from any origin. The package's components SHALL take data as props and SHALL NOT call the portal. A published version SHALL NOT change. Version 1.0.0 SHALL keep the components, class names and props of the fork's 1.0.0, so a team that installed it from the fork's staging gets the same package.

#### Scenario: Another team's page loads the stylesheet
- **WHEN** a page on another origin requests `/ui/v1/unicloud.css`
- **THEN** the answer carries `Access-Control-Allow-Origin: *`

#### Scenario: Another team installs the package
- **WHEN** a team downloads `/ui/v1/unicloud-ui-1.0.0.tgz` and runs `npm install` on the file
- **THEN** they get the components, their TypeScript types and the stylesheet
