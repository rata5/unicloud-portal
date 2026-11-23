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

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("user", me);
        answer.put("csrf", SignInController.csrfToken(request));
        answer.put("semester", semester);
        answer.put("nav", nav(user));
        return answer;
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
        return userClaims.profile(String.valueOf(user.id())).orElseThrow(PortalApiException::notFound);
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
        return userClaims.profile(String.valueOf(sub)).orElseThrow(PortalApiException::notFound);
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
