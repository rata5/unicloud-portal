package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.academic.domain.Faculty;
import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.enrollment.EnrollmentRules;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A teacher's offerings, rosters and rejection; the dean's faculty (portal-api.openapi.json:
 * portalTeachOfferings, portalRoster, portalReject, portalFaculty). Ported from the fork's
 * {@code portal_api/teach.rs}.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class TeachController {

    private static final Logger log = LoggerFactory.getLogger(TeachController.class);

    private static final String TEACH_SELECT = """
            SELECT o.id, c.code, c.title, o.semester,
                   (SELECT COUNT(*) FROM enrollments x WHERE x.offering_id = o.id AND x.status = 'ENROLLED') AS enrolled,
                   o.capacity, o.is_open
            FROM course_offerings o JOIN courses c ON c.id = o.course_id
            WHERE o.teacher_id = ?""";

    private static final RowMapper<Map<String, Object>> TEACH_OFFERING = (rs, row) -> {
        Map<String, Object> offering = new LinkedHashMap<>();
        offering.put("id", rs.getLong("id"));
        offering.put("code", rs.getString("code"));
        offering.put("title", rs.getString("title"));
        offering.put("semester", rs.getString("semester"));
        offering.put("enrolled", rs.getInt("enrolled"));
        offering.put("capacity", rs.getInt("capacity"));
        offering.put("is_open", rs.getBoolean("is_open"));
        return offering;
    };

    private final JdbcTemplate jdbc;
    private final EnrollmentRules enrollment;
    private final AcademicStructure academicStructure;

    public TeachController(JdbcTemplate jdbc, EnrollmentRules enrollment, AcademicStructure academicStructure) {
        this.jdbc = jdbc;
        this.enrollment = enrollment;
        this.academicStructure = academicStructure;
    }

    @GetMapping("/teach/offerings")
    public List<Map<String, Object>> offerings(PortalUser user) {
        PortalApiException.require(user, Permission.ENROLLMENT_REQUEST_ACCEPT_REJECT);
        List<Map<String, Object>> offerings = jdbc.query(TEACH_SELECT + " ORDER BY o.semester DESC, c.code", TEACH_OFFERING, user.id());
        offerings.forEach(offering -> Hypermedia.controls()
                .link("roster", Hypermedia.API + "/teach/offerings/" + offering.get("id")).on(offering));
        return offerings;
    }

    /** Only the offering's own teacher sees its roster; anyone else gets 404. */
    @GetMapping("/teach/offerings/{id}")
    public Map<String, Object> roster(PortalUser user, @PathVariable long id) {
        PortalApiException.require(user, Permission.ENROLLMENT_REQUEST_ACCEPT_REJECT);
        List<Map<String, Object>> offering = jdbc.query(TEACH_SELECT + " AND o.id = ?", TEACH_OFFERING, user.id(), id);
        if (offering.isEmpty()) {
            throw PortalApiException.notFound();
        }
        List<Map<String, Object>> roster = jdbc.query("""
                SELECT e.id AS enrollment_id, u.id AS student_id, CONCAT(u.first_name, ' ', u.last_name) AS name,
                       s.faculty_number, e.status
                FROM enrollments e JOIN users u ON u.id = e.student_id JOIN students s ON s.user_id = u.id
                WHERE e.offering_id = ?
                ORDER BY e.status, u.last_name, u.first_name""", (rs, row) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("enrollment_id", rs.getLong("enrollment_id"));
            entry.put("student_sub", String.valueOf(rs.getLong("student_id")));
            entry.put("name", rs.getString("name"));
            entry.put("faculty_number", rs.getString("faculty_number"));
            entry.put("status", rs.getString("status"));
            Hypermedia controls = Hypermedia.controls().link("student", Hypermedia.API + "/students/" + rs.getLong("student_id"));
            if ("ENROLLED".equals(rs.getString("status"))) {
                controls.template("reject", Hypermedia.form("Откажи", "POST",
                        Hypermedia.API + "/teach/enrollments/" + rs.getLong("enrollment_id") + "/reject"));
            }
            return controls.on(entry);
        }, id);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("offering", offering.getFirst());
        answer.put("roster", roster);
        return Hypermedia.controls()
                .link("self", Hypermedia.API + "/teach/offerings/" + id)
                .link("teaching", Hypermedia.API + "/teach/offerings")
                .on(answer);
    }

    @PostMapping("/teach/enrollments/{id}/reject")
    public ResponseEntity<Void> reject(PortalUser user, @PathVariable long id) {
        PortalApiException.require(user, Permission.ENROLLMENT_REQUEST_ACCEPT_REJECT);
        if (!enrollment.reject(user.id(), id)) {
            throw PortalApiException.conflict("Записването не може да бъде отхвърлено.");
        }
        log.info("Teacher {} rejected enrollment {}", user.id(), id);
        return ResponseEntity.ok().build();
    }

    /** The dean's faculty: its offerings of the current semester and its students. */
    @GetMapping("/faculty")
    public Map<String, Object> faculty(PortalUser user) {
        PortalApiException.require(user, Permission.STUDENT_PROFILE_READ_FACULTY);
        Faculty faculty = academicStructure.faculty(user.facultyCode()).orElseThrow(PortalApiException::notFound);
        List<Map<String, Object>> offerings = jdbc.query("""
                SELECT c.code, c.title, CONCAT(t.first_name, ' ', t.last_name) AS teacher,
                       (SELECT COUNT(*) FROM enrollments x WHERE x.offering_id = o.id AND x.status = 'ENROLLED') AS enrolled,
                       o.capacity
                FROM course_offerings o JOIN courses c ON c.id = o.course_id JOIN users t ON t.id = o.teacher_id
                WHERE c.faculty_code = ? AND o.semester = ?
                ORDER BY c.code""", (rs, row) -> {
            Map<String, Object> offering = new LinkedHashMap<>();
            offering.put("code", rs.getString("code"));
            offering.put("title", rs.getString("title"));
            offering.put("teacher", rs.getString("teacher"));
            offering.put("enrolled", rs.getInt("enrolled"));
            offering.put("capacity", rs.getInt("capacity"));
            return offering;
        }, faculty.code(), enrollment.currentSemester());
        List<Map<String, Object>> students = jdbc.query("""
                SELECT u.id, CONCAT(u.first_name, ' ', u.last_name) AS name, s.faculty_number, sp.name AS specialty,
                       s.study_year, s.status
                FROM students s JOIN users u ON u.id = s.user_id JOIN specialties sp ON sp.id = s.specialty_id
                WHERE sp.faculty_code = ?
                ORDER BY sp.name, s.study_year, u.last_name""", (rs, row) -> {
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("sub", String.valueOf(rs.getLong("id")));
            student.put("name", rs.getString("name"));
            student.put("faculty_number", rs.getString("faculty_number"));
            student.put("specialty", rs.getString("specialty"));
            student.put("study_year", rs.getInt("study_year"));
            student.put("status", rs.getString("status"));
            return Hypermedia.controls().link("student", Hypermedia.API + "/students/" + rs.getLong("id")).on(student);
        }, faculty.code());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("faculty", Map.of("code", faculty.code(), "name", faculty.name()));
        answer.put("semester", enrollment.currentSemester());
        answer.put("offerings", offerings);
        answer.put("students", students);
        return Hypermedia.controls().link("self", Hypermedia.API + "/faculty").on(answer);
    }
}
