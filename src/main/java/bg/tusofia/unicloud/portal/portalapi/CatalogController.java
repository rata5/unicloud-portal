package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.enrollment.EnrollmentRules;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue, enrolling, and a student's own enrollments (portal-api.openapi.json:
 * portalCourses, portalEnroll, portalMyEnrollments, portalCancel). Ported from the fork's
 * {@code portal_api/catalog.rs}. Enrollment acts on the signed-in student only.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class CatalogController {

    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

    private final JdbcTemplate jdbc;
    private final EnrollmentRules enrollment;

    public CatalogController(JdbcTemplate jdbc, EnrollmentRules enrollment) {
        this.jdbc = jdbc;
        this.enrollment = enrollment;
    }

    /** Open offerings of the current semester; {@code q} searches code and title, {@code faculty} filters. */
    @GetMapping("/courses")
    public List<Map<String, Object>> courses(
            PortalUser user,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String faculty) {

        PortalApiException.require(user, Permission.COURSE_OFFERING_READ);
        String search = q.strip().toLowerCase(Locale.ROOT);
        String like = "%" + search + "%";
        String facultyCode = faculty.strip().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                SELECT o.id, c.code, c.title, c.ects_credits AS ects, c.faculty_code AS faculty,
                       CONCAT(t.first_name, ' ', t.last_name) AS teacher,
                       (SELECT COUNT(*) FROM enrollments x WHERE x.offering_id = o.id AND x.status = 'ENROLLED') AS enrolled,
                       o.capacity, e.status AS my_status
                FROM course_offerings o
                JOIN courses c ON c.id = o.course_id
                JOIN users t ON t.id = o.teacher_id
                LEFT JOIN enrollments e ON e.offering_id = o.id AND e.student_id = ?
                WHERE o.semester = ? AND o.is_open = TRUE
                  AND (? = '' OR LOWER(c.code) LIKE ? OR LOWER(c.title) LIKE ?)
                  AND (? = '' OR c.faculty_code = ?)
                ORDER BY c.code""", (rs, row) -> {
            Map<String, Object> offering = new LinkedHashMap<>();
            offering.put("id", rs.getLong("id"));
            offering.put("code", rs.getString("code"));
            offering.put("title", rs.getString("title"));
            offering.put("ects", rs.getInt("ects"));
            offering.put("faculty", rs.getString("faculty"));
            offering.put("teacher", rs.getString("teacher"));
            offering.put("enrolled", rs.getInt("enrolled"));
            offering.put("capacity", rs.getInt("capacity"));
            offering.put("my_status", rs.getString("my_status"));
            return offering;
        }, user.id(), enrollment.currentSemester(), search, like, like, facultyCode, facultyCode);
    }

    /** 200 with the outcome ENROLLED, or 409 with the same shape and the reason (EnrollResult). */
    @PostMapping("/courses/{id}/enroll")
    public ResponseEntity<Map<String, String>> enroll(PortalUser user, @PathVariable long id) {
        PortalApiException.require(user, Permission.ENROLLMENT_REQUEST_CREATE);
        EnrollmentRules.Outcome outcome = enrollment.enroll(user.id(), id);
        if (outcome == EnrollmentRules.Outcome.ENROLLED) {
            log.info("Student {} enrolled in offering {}", user.id(), id);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("outcome", outcome.name());
        body.put("message", outcome.message());
        return ResponseEntity.status(outcome == EnrollmentRules.Outcome.ENROLLED ? 200 : 409).body(body);
    }

    @GetMapping("/enrollments")
    public List<Map<String, Object>> myEnrollments(PortalUser user) {
        PortalApiException.require(user, Permission.ENROLLMENT_REQUEST_CREATE);
        return jdbc.query("""
                SELECT e.id, c.code, c.title, o.semester, CONCAT(t.first_name, ' ', t.last_name) AS teacher, e.status
                FROM enrollments e
                JOIN course_offerings o ON o.id = e.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN users t ON t.id = o.teacher_id
                WHERE e.student_id = ?
                ORDER BY o.semester DESC, c.code""", (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("code", rs.getString("code"));
            item.put("title", rs.getString("title"));
            item.put("semester", rs.getString("semester"));
            item.put("teacher", rs.getString("teacher"));
            item.put("status", rs.getString("status"));
            return item;
        }, user.id());
    }

    @PostMapping("/enrollments/{id}/cancel")
    public ResponseEntity<Void> cancel(PortalUser user, @PathVariable long id) {
        PortalApiException.require(user, Permission.ENROLLMENT_REQUEST_CREATE);
        if (!enrollment.cancel(user.id(), id)) {
            throw PortalApiException.conflict("Записването не може да бъде отказано.");
        }
        return ResponseEntity.ok().build();
    }
}
