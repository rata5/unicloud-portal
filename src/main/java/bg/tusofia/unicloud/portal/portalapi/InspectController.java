package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.accounts.application.AccountCreation;
import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import bg.tusofia.unicloud.portal.user.domain.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The academic office: students and their status, courses and offerings
 * (portal-api.openapi.json: portalInspectStudents, portalCreateStudent, portalSetStatus,
 * portalInspectCourses, portalCreateCourse, portalCreateOffering, portalToggleOffering). Ported
 * from the fork's {@code portal_api/inspect.rs}.
 */
@RestController
@RequestMapping("/api/v1/portal/inspect")
public class InspectController {

    private static final Logger log = LoggerFactory.getLogger(InspectController.class);
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INTERRUPTED", "GRADUATED");
    private static final int MAX_STUDENTS = 300;

    public record NewStudent(
            String email,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @JsonProperty("faculty_number") String facultyNumber,
            @JsonProperty("specialty_id") Integer specialtyId,
            @JsonProperty("study_year") Integer studyYear,
            String password) {
    }

    public record StatusChange(String status) {
    }

    public record NewCourse(
            String code,
            String title,
            @JsonProperty("ects_credits") Integer ectsCredits,
            @JsonProperty("faculty_code") String facultyCode) {
    }

    public record NewOffering(
            @JsonProperty("course_code") String courseCode,
            String semester,
            @JsonProperty("teacher_sub") String teacherSub,
            Integer capacity) {
    }

    private final JdbcTemplate jdbc;
    private final AccountCreation accounts;
    private final AcademicStructure academicStructure;

    public InspectController(JdbcTemplate jdbc, AccountCreation accounts, AcademicStructure academicStructure) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.academicStructure = academicStructure;
    }

    /** At most 300 students; {@code q} searches name, faculty number and email. */
    @GetMapping("/students")
    public List<Map<String, Object>> students(PortalUser user, @RequestParam(defaultValue = "") String q) {
        if (!user.has(Permission.STUDENT_CREATE) && !user.has(Permission.STUDENT_PROFILE_READ_ANY)) {
            throw PortalApiException.forbidden();
        }
        String search = q.strip().toLowerCase(Locale.ROOT);
        String like = "%" + search + "%";
        return jdbc.query("""
                SELECT u.id, CONCAT(u.first_name, ' ', u.last_name) AS name, u.email, s.faculty_number,
                       CASE WHEN sp.id IS NULL THEN s.specialty ELSE CONCAT(sp.faculty_code, ' · ', sp.name) END AS specialty,
                       s.study_year, s.status
                FROM students s JOIN users u ON u.id = s.user_id LEFT JOIN specialties sp ON sp.id = s.specialty_id
                WHERE ? = '' OR LOWER(CONCAT(u.first_name, ' ', u.last_name)) LIKE ?
                   OR LOWER(s.faculty_number) LIKE ? OR LOWER(u.email) LIKE ?
                ORDER BY u.last_name, u.first_name, u.id
                LIMIT ?""", (rs, row) -> {
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("sub", String.valueOf(rs.getLong("id")));
            student.put("name", rs.getString("name"));
            student.put("email", rs.getString("email"));
            student.put("faculty_number", rs.getString("faculty_number"));
            student.put("specialty", rs.getString("specialty"));
            student.put("study_year", rs.getInt("study_year"));
            student.put("status", rs.getString("status"));
            return student;
        }, search, like, like, like, MAX_STUDENTS);
    }

    /** A new student who must change the first password; 201 with {@code {sub}}. */
    @PostMapping("/students")
    public ResponseEntity<Map<String, String>> createStudent(PortalUser user, @RequestBody NewStudent form) {
        PortalApiException.require(user, Permission.STUDENT_CREATE);
        long id = accounts.create(new AccountCreation.NewAccount(form.email(), form.password(), form.firstName(),
                form.lastName(), Role.STUDENT, null,
                new AccountCreation.NewRecord(form.facultyNumber(), form.specialtyId(), form.studyYear())));
        log.info("Student {} created by user {}", id, user.id());
        return ResponseEntity.status(201).body(Map.of("sub", String.valueOf(id)));
    }

    @PutMapping("/students/{sub}/status")
    public ResponseEntity<Void> setStatus(PortalUser user, @PathVariable long sub, @RequestBody StatusChange change) {
        PortalApiException.require(user, Permission.STUDENT_CREATE);
        if (change.status() == null || !STATUSES.contains(change.status())) {
            throw PortalApiException.invalid("Невалиден статус.");
        }
        if (jdbc.update("UPDATE students SET status = ? WHERE user_id = ?", change.status(), sub) == 0) {
            throw PortalApiException.notFound();
        }
        log.info("Student {} is now {}, by user {}", sub, change.status(), user.id());
        return ResponseEntity.noContent().build();
    }

    /** Every course, every offering, and the teachers an offering can have. */
    @GetMapping("/courses")
    public Map<String, Object> courses(PortalUser user) {
        if (!user.has(Permission.COURSE_CREATE_MODIFY) && !user.has(Permission.COURSE_OFFERING_CREATE_MODIFY)) {
            throw PortalApiException.forbidden();
        }
        List<Map<String, Object>> courses = jdbc.query(
                "SELECT code, title, ects_credits, faculty_code FROM courses ORDER BY code", (rs, row) -> {
                    Map<String, Object> course = new LinkedHashMap<>();
                    course.put("code", rs.getString("code"));
                    course.put("title", rs.getString("title"));
                    course.put("ects", rs.getInt("ects_credits"));
                    course.put("faculty", rs.getString("faculty_code"));
                    return course;
                });
        List<Map<String, Object>> offerings = jdbc.query("""
                SELECT o.id, c.code, o.semester, CONCAT(t.first_name, ' ', t.last_name) AS teacher,
                       (SELECT COUNT(*) FROM enrollments x WHERE x.offering_id = o.id AND x.status = 'ENROLLED') AS enrolled,
                       o.capacity, o.is_open
                FROM course_offerings o JOIN courses c ON c.id = o.course_id JOIN users t ON t.id = o.teacher_id
                ORDER BY o.semester DESC, c.code""", (rs, row) -> {
            Map<String, Object> offering = new LinkedHashMap<>();
            offering.put("id", rs.getLong("id"));
            offering.put("code", rs.getString("code"));
            offering.put("semester", rs.getString("semester"));
            offering.put("teacher", rs.getString("teacher"));
            offering.put("enrolled", rs.getInt("enrolled"));
            offering.put("capacity", rs.getInt("capacity"));
            offering.put("is_open", rs.getBoolean("is_open"));
            return offering;
        });
        List<Map<String, Object>> teachers = jdbc.query("""
                SELECT id, CONCAT(last_name, ' ', first_name) AS name, faculty_code
                FROM users WHERE role = 'TEACHER' AND enabled = TRUE
                ORDER BY last_name, first_name""", (rs, row) -> {
            Map<String, Object> teacher = new LinkedHashMap<>();
            teacher.put("sub", String.valueOf(rs.getLong("id")));
            teacher.put("name", rs.getString("name"));
            teacher.put("faculty", rs.getString("faculty_code"));
            return teacher;
        });
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("courses", courses);
        answer.put("offerings", offerings);
        answer.put("teachers", teachers);
        return answer;
    }

    @PostMapping("/courses")
    public ResponseEntity<Void> createCourse(PortalUser user, @RequestBody NewCourse form) {
        PortalApiException.require(user, Permission.COURSE_CREATE_MODIFY);
        String code = form.code() == null ? "" : form.code().strip().toUpperCase(Locale.ROOT);
        if (code.isEmpty() || code.length() > 16 || !code.chars().allMatch(c -> (c < 128 && Character.isLetterOrDigit(c)) || c == '-')) {
            throw PortalApiException.invalid("Кодът трябва да е до 16 латински букви, цифри или тирета.");
        }
        String title = form.title() == null ? "" : form.title().strip();
        if (title.isEmpty() || title.length() > 200 || form.ectsCredits() == null || form.ectsCredits() < 1 || form.ectsCredits() > 30) {
            throw PortalApiException.invalid("Попълнете наименование и ECTS от 1 до 30.");
        }
        String faculty = academicStructure.faculty(form.facultyCode())
                .orElseThrow(() -> PortalApiException.invalid("Няма такъв факултет.")).code();
        try {
            jdbc.update("INSERT INTO courses (code, title, ects_credits, faculty_code) VALUES (?, ?, ?, ?)",
                    code, title, form.ectsCredits(), faculty);
        } catch (DuplicateKeyException e) {
            throw PortalApiException.conflict("Дисциплина с този код вече съществува.");
        }
        log.info("Course {} created by user {}", code, user.id());
        return ResponseEntity.status(201).build();
    }

    /** An offering of a course for a semester, with a teacher of the course's faculty. */
    @PostMapping("/offerings")
    public ResponseEntity<Void> createOffering(PortalUser user, @RequestBody NewOffering form) {
        PortalApiException.require(user, Permission.COURSE_OFFERING_CREATE_MODIFY);
        String semester = form.semester() == null ? "" : form.semester().strip();
        if (form.capacity() == null || form.capacity() < 1 || form.capacity() > 32767
                || semester.isEmpty() || semester.length() > 16) {
            throw PortalApiException.invalid("Местата трябва да са поне 1, а семестърът да е попълнен.");
        }
        long teacher;
        try {
            teacher = Long.parseLong(form.teacherSub() == null ? "" : form.teacherSub().strip());
        } catch (NumberFormatException e) {
            throw PortalApiException.invalid("Няма такъв преподавател.");
        }
        List<Map<String, Object>> match = jdbc.queryForList("""
                SELECT c.id AS course_id, c.faculty_code AS course_faculty, t.faculty_code AS teacher_faculty
                FROM courses c, users t
                WHERE t.id = ? AND t.role = 'TEACHER' AND c.code = ?""", teacher,
                form.courseCode() == null ? "" : form.courseCode().strip().toUpperCase(Locale.ROOT));
        if (match.isEmpty() || match.getFirst().get("course_faculty") == null
                || !match.getFirst().get("course_faculty").equals(match.getFirst().get("teacher_faculty"))) {
            throw PortalApiException.invalid("Преподавателят трябва да е от факултета на дисциплината.");
        }
        try {
            jdbc.update("INSERT INTO course_offerings (course_id, semester, teacher_id, capacity, is_open) VALUES (?, ?, ?, ?, TRUE)",
                    match.getFirst().get("course_id"), semester, teacher, form.capacity());
        } catch (DuplicateKeyException e) {
            throw PortalApiException.conflict("Този курс вече съществува за семестъра.");
        }
        log.info("Offering of {} in {} created by user {}", form.courseCode(), semester, user.id());
        return ResponseEntity.status(201).build();
    }

    /** Opens or closes enrollment for an offering; {@code {enabled}} is whether it is open now. */
    @PostMapping("/offerings/{id}/toggle")
    public Map<String, Boolean> toggleOffering(PortalUser user, @PathVariable long id) {
        PortalApiException.require(user, Permission.COURSE_OFFERING_CREATE_MODIFY);
        if (jdbc.update("UPDATE course_offerings SET is_open = NOT is_open WHERE id = ?", id) == 0) {
            throw PortalApiException.notFound();
        }
        Boolean open = jdbc.queryForObject("SELECT is_open FROM course_offerings WHERE id = ?", Boolean.class, id);
        return Map.of("enabled", Boolean.TRUE.equals(open));
    }
}
