package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.accounts.web.SignInController;
import bg.tusofia.unicloud.portal.sso.application.SsoUserClaims;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import bg.tusofia.unicloud.portal.user.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is signed in, the form options, and profiles (portal-api.openapi.json: portalMe,
 * portalOptions, portalProfile, portalStudent). Ported from the fork's {@code portal_api/me.rs}.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class MeController {

    private final SsoUserClaims userClaims;
    private final AcademicStructure academicStructure;
    private final JdbcTemplate jdbc;
    private final String semester;

    public MeController(SsoUserClaims userClaims, AcademicStructure academicStructure, JdbcTemplate jdbc,
                        @Value("${app.current-semester}") String semester) {
        this.userClaims = userClaims;
        this.academicStructure = academicStructure;
        this.jdbc = jdbc;
        this.semester = semester.strip();
    }

    /** The user, the session's CSRF token, the current semester and the app's navigation. */
    @GetMapping("/me")
    public Map<String, Object> me(PortalUser user, HttpServletRequest request) {
        Map<String, Object> profile = userClaims.profile(String.valueOf(user.id())).orElseThrow(PortalApiException::unauthenticated);
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("sub", String.valueOf(user.id()));
        me.put("email", user.email());
        me.put("given_name", user.firstName());
        me.put("family_name", user.lastName());
        me.put("name", user.name());
        me.put("role", user.role().name());
        me.put("role_label", roleLabel(user.role()));
        me.put("faculty", profile.get("faculty"));
        me.put("permissions", user.permissions().stream().map(Permission::name).toList());

        String csrf = SignInController.csrfToken(request);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("user", me);
        answer.put("csrf", csrf);
        answer.put("semester", semester);
        answer.put("nav", nav(user));
        return entryControls(user, csrf).on(answer);
    }

    /**
     * The entry point's links (one per page the user may open, as in nav) and the templates for
     * actions on whole lists (react-hypermedia-ui design, "Hypermedia on top").
     */
    private Hypermedia entryControls(PortalUser user, String csrf) {
        Hypermedia controls = Hypermedia.controls()
                .link("self", Hypermedia.API + "/me")
                .link("options", Hypermedia.API + "/options");
        if (user.has(Permission.STUDENT_PROFILE_READ_OWN)) {
            controls.link("profile", Hypermedia.API + "/profile");
        }
        if (user.has(Permission.COURSE_OFFERING_READ)) {
            controls.templatedLink("courses", Hypermedia.API + "/courses{?q,faculty}");
        }
        if (user.has(Permission.ENROLLMENT_REQUEST_CREATE)) {
            controls.link("enrollments", Hypermedia.API + "/enrollments");
        }
        if (user.has(Permission.ENROLLMENT_REQUEST_ACCEPT_REJECT)) {
            controls.link("teaching", Hypermedia.API + "/teach/offerings");
        }
        if (user.has(Permission.STUDENT_PROFILE_READ_FACULTY)) {
            controls.link("faculty", Hypermedia.API + "/faculty");
        }
        if (user.has(Permission.STUDENT_CREATE) || user.has(Permission.STUDENT_PROFILE_READ_ANY)) {
            controls.templatedLink("students", Hypermedia.API + "/inspect/students{?q}");
        }
        if (user.has(Permission.COURSE_CREATE_MODIFY) || user.has(Permission.COURSE_OFFERING_CREATE_MODIFY)) {
            controls.link("catalogue", Hypermedia.API + "/inspect/courses");
        }
        if (user.has(Permission.STAFF_CREATE)) {
            controls.link("staff", Hypermedia.API + "/admin/staff");
        }
        if (user.role() == Role.ADMIN) {
            controls.link("modules", Hypermedia.API + "/admin/modules");
        }
        if (user.has(Permission.STUDENT_PROFILE_READ_ANY) || user.has(Permission.STUDENT_PROFILE_READ_FACULTY)
                || user.has(Permission.STUDENT_PROFILE_READ_ENROLLED)) {
            controls.templatedLink("student", Hypermedia.API + "/students/{sub}");
        }
        controls.template("logout", Hypermedia.form("Изход", "POST", "/logout")
                .contentType("application/x-www-form-urlencoded")
                .property(Hypermedia.field("csrf", "").type("hidden").value(csrf)));
        if (user.has(Permission.STUDENT_CREATE)) {
            controls.template("createStudent", Hypermedia.form("Нов студент", "POST", Hypermedia.API + "/inspect/students")
                    .property(Hypermedia.field("email", "Имейл").required().type("email").length(0, 254))
                    .property(Hypermedia.field("first_name", "Име").required().length(0, 100))
                    .property(Hypermedia.field("last_name", "Фамилия").required().length(0, 100))
                    .property(Hypermedia.field("faculty_number", "Факултетен номер").required().length(0, 20))
                    .property(Hypermedia.field("specialty_id", "Специалност").required().options(
                            academicStructure.specialties().stream()
                                    .map(s -> Hypermedia.choice(s.facultyCode() + " · " + s.name(), s.id())).toList()))
                    .property(Hypermedia.field("study_year", "Курс").required().range(1, 6))
                    .property(Hypermedia.field("password", "Начална парола").required().type("password").length(8, 72)));
        }
        if (user.has(Permission.STAFF_CREATE)) {
            List<Map<String, Object>> roles = new ArrayList<>();
            for (Role role : List.of(Role.TEACHER, Role.DEAN, Role.INSPECTOR, Role.ADMIN)) {
                if (role != Role.ADMIN || user.role() == Role.ADMIN) {
                    roles.add(Hypermedia.choice(roleLabel(role), role.name()));
                }
            }
            controls.template("createStaff", Hypermedia.form("Нов служител", "POST", Hypermedia.API + "/admin/staff")
                    .property(Hypermedia.field("email", "Имейл").required().type("email").length(0, 254))
                    .property(Hypermedia.field("first_name", "Име").required().length(0, 100))
                    .property(Hypermedia.field("last_name", "Фамилия").required().length(0, 100))
                    .property(Hypermedia.field("role", "Роля").required().options(roles))
                    .property(Hypermedia.field("faculty_code", "Факултет (за преподавател, декан и инспектор)").options(
                            academicStructure.faculties().stream()
                                    .map(f -> Hypermedia.choice(f.code() + " · " + f.name(), f.code())).toList()))
                    .property(Hypermedia.field("password", "Начална парола").required().type("password").length(8, 72)));
        }
        if (user.role() == Role.ADMIN) {
            controls.template("registerModule", Hypermedia.form("Регистрирай модул", "POST", Hypermedia.API + "/admin/modules")
                    .property(Hypermedia.field("clientId", "Client ID").required().regex("[a-z0-9][a-z0-9-]{1,62}"))
                    .property(Hypermedia.field("name", "Име").required().length(1, 200))
                    .property(Hypermedia.field("redirectUris", "Адреси за връщане").required().type("textarea"))
                    .property(Hypermedia.field("postLogoutRedirectUris", "Адреси след изход").type("textarea"))
                    .property(Hypermedia.field("directory", "Достъп до директорията").type("checkbox")));
        }
        return controls;
    }

    @GetMapping("/options")
    public Map<String, Object> options(PortalUser user) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("faculties", academicStructure.faculties().stream()
                .map(faculty -> Map.of("code", faculty.code(), "name", faculty.name())).toList());
        answer.put("specialties", academicStructure.specialties().stream().map(specialty -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", specialty.id());
            item.put("faculty_code", specialty.facultyCode());
            item.put("name", specialty.name());
            return item;
        }).toList());
        answer.put("semester", semester);
        return answer;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(PortalUser user) {
        return Hypermedia.controls().link("self", Hypermedia.API + "/profile")
                .on(userClaims.profile(String.valueOf(user.id())).orElseThrow(PortalApiException::notFound));
    }

    /**
     * Another student's profile, only with a permission that covers that student: ANY, FACULTY
     * (the reader's faculty) or ENROLLED (in an offering the reader teaches). Anyone else gets 404,
     * so the portal does not reveal that the student exists (academic-profile spec).
     */
    @GetMapping("/students/{sub}")
    public Map<String, Object> student(PortalUser user, @PathVariable long sub) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT sp.faculty_code,
                       (SELECT COUNT(*) FROM enrollments e JOIN course_offerings o ON o.id = e.offering_id
                        WHERE e.student_id = s.user_id AND o.teacher_id = ? AND e.status = 'ENROLLED') AS taught
                FROM students s LEFT JOIN specialties sp ON sp.id = s.specialty_id
                WHERE s.user_id = ?""", user.id(), sub);
        if (rows.isEmpty()) {
            throw PortalApiException.notFound();
        }
        Map<String, Object> row = rows.getFirst();
        boolean sameFaculty = user.facultyCode() != null && user.facultyCode().equals(row.get("faculty_code"));
        boolean taught = ((Number) row.get("taught")).intValue() > 0;
        boolean allowed = (user.id() == sub && user.has(Permission.STUDENT_PROFILE_READ_OWN))
                || user.has(Permission.STUDENT_PROFILE_READ_ANY)
                || (sameFaculty && user.has(Permission.STUDENT_PROFILE_READ_FACULTY))
                || (taught && user.has(Permission.STUDENT_PROFILE_READ_ENROLLED));
        if (!allowed) {
            throw PortalApiException.notFound();
        }
        return Hypermedia.controls().link("self", Hypermedia.API + "/students/" + sub)
                .on(userClaims.profile(String.valueOf(sub)).orElseThrow(PortalApiException::notFound));
    }

    /** The role in Bulgarian, as the app shows it. */
    static String roleLabel(Role role) {
        return switch (role) {
            case ADMIN -> "Администратор";
            case INSPECTOR -> "Инспектор";
            case TEACHER -> "Преподавател";
            case DEAN -> "Декан";
            case STUDENT -> "Студент";
        };
    }

    /** The app's navigation (paths under /app), by permission; the server checks again on every call. */
    static List<Map<String, String>> nav(PortalUser user) {
        List<Map<String, String>> links = new ArrayList<>();
        links.add(navItem("/home", "Начало"));
        if (user.has(Permission.STUDENT_PROFILE_READ_OWN)) {
            links.add(navItem("/profile", "Профил"));
        }
        if (user.has(Permission.COURSE_OFFERING_READ)) {
            links.add(navItem("/courses", "Курсове"));
        }
        if (user.has(Permission.ENROLLMENT_REQUEST_CREATE)) {
            links.add(navItem("/enrollments", "Моите записвания"));
        }
        if (user.has(Permission.ENROLLMENT_REQUEST_ACCEPT_REJECT)) {
            links.add(navItem("/teach", "Моите курсове"));
        }
        if (user.has(Permission.STUDENT_PROFILE_READ_FACULTY)) {
            links.add(navItem("/faculty", "Факултет"));
        }
        if (user.has(Permission.STUDENT_CREATE) || user.has(Permission.STUDENT_PROFILE_READ_ANY)) {
            links.add(navItem("/inspect/students", "Студенти"));
        }
        if (user.has(Permission.COURSE_CREATE_MODIFY) || user.has(Permission.COURSE_OFFERING_CREATE_MODIFY)) {
            links.add(navItem("/inspect/courses", "Дисциплини"));
        }
        if (user.has(Permission.STAFF_CREATE)) {
            links.add(navItem("/admin/staff", "Служители"));
        }
        if (user.role() == Role.ADMIN) {
            links.add(navItem("/admin/modules", "Модули"));
        }
        return links;
    }

    private static Map<String, String> navItem(String path, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("path", path);
        item.put("label", label);
        return item;
    }
}
