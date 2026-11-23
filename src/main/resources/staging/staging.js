// The staging page's script (GET /staging/staging.js). A file, not inline, because the
// Content-Security-Policy allows scripts only from the portal's own files (SecurityHeaders).
(() => {
    const $ = (id) => document.getElementById(id);
    let roleDefaults = {};
    let allPermissions = [];
    const FACULTY_ROLES = ['TEACHER', 'DEAN', 'INSPECTOR'];

    async function call(method, url, body) {
        const response = await fetch(url, {
            method,
            headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
            body: body ? JSON.stringify(body) : undefined,
        });
        const text = await response.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
        if (!response.ok) {
            const fields = data && data.fieldErrors ? data.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ') : '';
            const reason = (data && (data.message || data.error)) || `Error ${response.status}`;
            throw new Error(fields ? `${reason} (${fields})` : reason);
        }
        return data;
    }

    function show(element, text, ok) {
        element.textContent = text;
        element.className = 'message ' + (ok ? 'message-success' : 'message-error');
        element.hidden = false;
    }

    function permissionBoxes(container, checked, prefix) {
        container.replaceChildren();
        for (const name of allPermissions) {
            const label = document.createElement('label');
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.value = name;
            box.checked = checked.includes(name);
            box.id = prefix + name;
            label.append(box, document.createTextNode(name));
            container.append(label);
        }
    }

    const ticked = (container) => [...container.querySelectorAll('input:checked')].map((b) => b.value);

    function onRoleChange() {
        const role = $('role').value;
        permissionBoxes($('perms'), roleDefaults[role] || [], 'new-');
        $('student-fields').hidden = role !== 'STUDENT';
        $('faculty-field').hidden = !FACULTY_ROLES.includes(role);
    }

    function cell(text, className) {
        const td = document.createElement('td');
        td.textContent = text;
        if (className) td.className = className;
        return td;
    }

    async function loadUsers() {
        const users = await call('GET', '/api/v1/staging/users');
        const body = $('users');
        body.replaceChildren();
        for (const user of users) {
            const tr = document.createElement('tr');
            const actions = document.createElement('td');
            actions.className = 'row-actions';
            const edit = document.createElement('button');
            edit.type = 'button';
            edit.className = 'btn-secondary';
            edit.textContent = 'Edit permissions';
            edit.addEventListener('click', () => openEditor(tr, user));
            actions.append(edit);
            tr.append(cell(user.id), cell(user.email + (user.student ? ' (student record)' : '')), cell(user.role),
                cell(user.faculty || ''), cell(user.permissions.join(', ') || 'none', 'perm-cell'), actions);
            body.append(tr);
        }
    }

    function openEditor(row, user) {
        if (row.nextElementSibling && row.nextElementSibling.classList.contains('edit-row')) {
            row.nextElementSibling.remove();
            return;
        }
        const editRow = document.createElement('tr');
        editRow.className = 'edit-row';
        const td = document.createElement('td');
        td.colSpan = 6;
        const boxes = document.createElement('div');
        boxes.className = 'perms';
        permissionBoxes(boxes, user.permissions, `u${user.id}-`);
        const save = document.createElement('button');
        save.type = 'button';
        save.textContent = 'Save permissions';
        const message = document.createElement('p');
        message.hidden = true;
        save.addEventListener('click', async () => {
            try {
                await call('PUT', `/api/v1/staging/users/${encodeURIComponent(user.id)}/permissions`, { permissions: ticked(boxes) });
                await loadUsers();
            } catch (e) {
                show(message, e.message, false);
            }
        });
        td.append(boxes, save, message);
        editRow.append(td);
        row.after(editRow);
    }

    $('role').addEventListener('change', onRoleChange);

    $('user-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const role = $('role').value;
        const request = {
            email: $('email').value.trim(),
            password: $('password').value,
            firstName: $('first-name').value.trim(),
            lastName: $('last-name').value.trim(),
            role,
            permissions: ticked($('perms')),
        };
        if (FACULTY_ROLES.includes(role)) {
            request.faculty = $('faculty').value;
        }
        if (role === 'STUDENT' && $('faculty-number').value.trim()) {
            request.student = {
                facultyNumber: $('faculty-number').value.trim(),
                specialty: $('specialty').value,
                studyYear: Number($('study-year').value),
                status: $('status').value,
            };
        }
        try {
            const user = await call('POST', '/api/v1/staging/users', request);
            show($('user-message'), `Created ${user.email} (id ${user.id}). Log in with it on the portal or through your module.`, true);
            $('user-form').reset();
            onRoleChange();
            await loadUsers();
        } catch (e) {
            show($('user-message'), e.message, false);
        }
    });

    $('client-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const redirectUris = $('redirects').value.split('\n').map((s) => s.trim()).filter(Boolean);
        try {
            const client = await call('POST', '/api/v1/staging/sso-clients', {
                clientId: $('client-id').value.trim(),
                name: $('client-name').value.trim(),
                redirectUris,
                directory: $('client-directory').checked,
            });
            const message = $('client-message');
            show(message, `Registered ${client.clientId}${$('client-directory').checked ? ' with the directory grant' : ''}. Its secret, shown only now: `, true);
            const secret = document.createElement('div');
            secret.className = 'secret';
            secret.textContent = client.clientSecret;
            message.append(secret);
        } catch (e) {
            show($('client-message'), e.message, false);
        }
    });

    (async () => {
        try {
            const roles = await call('GET', '/api/v1/staging/roles');
            roleDefaults = roles.roleDefaults;
            allPermissions = roles.permissions;
            const faculties = await call('GET', '/api/v1/staging/faculties');
            for (const faculty of faculties) {
                const option = document.createElement('option');
                option.value = faculty.code;
                option.textContent = `${faculty.code}: ${faculty.name}`;
                $('faculty').append(option);
                const group = document.createElement('optgroup');
                group.label = faculty.code;
                for (const specialty of faculty.specialties) {
                    const item = document.createElement('option');
                    item.value = String(specialty.id);
                    item.textContent = specialty.name;
                    group.append(item);
                }
                $('specialty').append(group);
            }
            for (const role of Object.keys(roleDefaults)) {
                const option = document.createElement('option');
                option.value = role;
                option.textContent = role;
                $('role').append(option);
            }
            onRoleChange();
            await loadUsers();
        } catch (e) {
            show($('user-message'), 'The staging tools are not available here: ' + e.message, false);
        }
    })();
})();
