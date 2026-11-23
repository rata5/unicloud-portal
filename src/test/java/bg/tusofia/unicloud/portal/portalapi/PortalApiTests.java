package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.support.PortalBrowser;
import bg.tusofia.unicloud.portal.support.PortalBrowser.Answer;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static bg.tusofia.unicloud.portal.support.PortalContract.check;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The app's JSON API (portal-api.openapi.json; react-hypermedia-ui and complete-java-portal
 * specs), through a signed-in browser for each role, as the React app calls it. Every answer is
 * checked against the pinned contract. Each test makes its own faculty and people: the local
 * database outlives a run, and a faculty has one dean.
 */
@SpringBootTest
@ActiveProfiles("staging")
class PortalApiTests {

    private static final String PASSWORD = "a-long-password";
    private static final String SEMESTER = "2026-27-W";
    private static final String API = "/api/v1/portal";

    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter sessionRepositoryFilter;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter, springSecurityFilterChain)
                .build();
    }

    @Test
    void eachRoleGetsItsOwnNavigation() throws Exception {
        School school = newSchool();

        assertThat(nav(school.student())).containsExactly("/home", "/profile", "/courses", "/enrollments");
        assertThat(nav(school.teacher())).containsExactly("/home", "/courses", "/teach");
        assertThat(nav(school.dean())).containsExactly("/home", "/courses", "/faculty");
        assertThat(nav(school.inspector())).containsExactly("/home", "/courses", "/inspect/students", "/inspect/courses");
        assertThat(nav(school.admin())).containsExactly("/home", "/admin/staff", "/admin/modules");

        Answer me = school.dean().get(API + "/me");
        check("GET", API + "/me", 200, me.json());
        assertThat(me.json().get("user").get("role_label").asString()).isEqualTo("Декан");
        assertThat(me.json().get("user").get("faculty").get("code").asString()).isEqualTo(school.faculty());
        assertThat(me.json().get("semester").asString()).isEqualTo(SEMESTER);
        assertThat(me.json().get("csrf").asString()).isNotBlank();

        Answer options = school.student().get(API + "/options");
        check("GET", API + "/options", 200, options.json());
        Answer profile = school.student().get(API + "/profile");
        check("GET", API + "/profile", 200, profile.json());
        assertThat(profile.json().get("student").get("specialty").asString()).isEqualTo(school.specialtyName());
    }

    @Test
    void aStudentEnrollsCancelsAndCanBeRejected() throws Exception {
        School school = newSchool();
        long offering = school.offering(30);

        Answer courses = school.student().get(API + "/courses?faculty=" + school.faculty());
        check("GET", API + "/courses", 200, courses.json());
        JsonNode item = find(courses.json(), "id", offering);
        assertThat(item.get("enrolled").asInt()).isZero();
        assertThat(item.get("my_status").isNull()).isTrue();

        Answer enrolled = school.student().post(API + "/courses/" + offering + "/enroll", null);
        check("POST", API + "/courses/{id}/enroll", 200, enrolled.json());
        assertThat(enrolled.json().get("outcome").asString()).isEqualTo("ENROLLED");
        assertThat(find(school.student().get(API + "/courses").json(), "id", offering).get("my_status").asString())
                .isEqualTo("ENROLLED");

        Answer again = school.student().post(API + "/courses/" + offering + "/enroll", null);
        assertThat(again.status()).isEqualTo(409);
        check("POST", API + "/courses/{id}/enroll", 409, again.json());
        assertThat(again.json().get("outcome").asString()).isEqualTo("ALREADY_ENROLLED");

        Answer mine = school.student().get(API + "/enrollments");
        check("GET", API + "/enrollments", 200, mine.json());
        long enrollment = find(mine.json(), "code", school.course()).get("id").asLong();

        assertThat(school.student().post(API + "/enrollments/" + enrollment + "/cancel", null).status()).isEqualTo(200);
        assertThat(school.student().post(API + "/enrollments/" + enrollment + "/cancel", null).status()).isEqualTo(409);
        assertThat(school.student().post(API + "/courses/" + offering + "/enroll", null).status())
                .as("a cancelled request may come back").isEqualTo(200);

        // The teacher sees the roster and rejects; the student cannot come back
        Answer roster = school.teacher().get(API + "/teach/offerings/" + offering);
        check("GET", API + "/teach/offerings/{id}", 200, roster.json());
        JsonNode entry = roster.json().get("roster").get(0);
        assertThat(entry.get("student_sub").asString()).isEqualTo(String.valueOf(school.studentId()));
        check("GET", API + "/teach/offerings", 200, school.teacher().get(API + "/teach/offerings").json());

        assertThat(school.teacher().post(API + "/teach/enrollments/" + entry.get("enrollment_id").asLong() + "/reject", null)
                .status()).isEqualTo(200);
        Answer rejected = school.student().post(API + "/courses/" + offering + "/enroll", null);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.json().get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM enrollments WHERE id = ?", Long.class,
                entry.get("enrollment_id").asLong())).isEqualTo(school.teacherId());
    }

    @Test
    void enrollmentIsRefusedWhenFullClosedOrNotActive() throws Exception {
        School school = newSchool();
        long offering = school.offering(1);

        assertThat(school.student().post(API + "/courses/" + offering + "/enroll", null).status()).isEqualTo(200);
        assertThat(outcome(school.secondStudent(), offering)).isEqualTo("FULL");

        Answer toggled = school.inspector().post(API + "/inspect/offerings/" + offering + "/toggle", null);
        check("POST", API + "/inspect/offerings/{id}/toggle", 200, toggled.json());
        assertThat(toggled.json().get("enabled").asBoolean()).isFalse();
        assertThat(outcome(school.secondStudent(), offering)).isEqualTo("CLOSED");
        school.inspector().post(API + "/inspect/offerings/" + offering + "/toggle", null);

        assertThat(school.inspector().put(API + "/inspect/students/" + school.secondStudentId() + "/status",
                "{\"status\": \"INTERRUPTED\"}").status()).isEqualTo(204);
        assertThat(outcome(school.secondStudent(), offering)).isEqualTo("NOT_ACTIVE");
        assertThat(outcome(school.student(), 999_999_999L)).isEqualTo("NO_SUCH_OFFERING");

        // A change without the session's CSRF token is refused and changes nothing
        Answer forged = school.student().postWithoutCsrf(API + "/courses/" + offering + "/enroll");
        assertThat(forged.status()).isEqualTo(403);
        assertThat(forged.json().get("error").asString()).isEqualTo("forbidden");
    }

    @Test
    void anotherStudentsProfileOnlyWithAPermissionThatCoversThem() throws Exception {
        School school = newSchool();
        School elsewhere = newSchool();
        long offering = school.offering(30);
        school.student().post(API + "/courses/" + offering + "/enroll", null);
        String student = API + "/students/" + school.studentId();

        Answer byDean = school.dean().get(student);
        check("GET", API + "/students/{sub}", 200, byDean.json());
        assertThat(elsewhere.dean().get(student).status()).as("a dean of another faculty").isEqualTo(404);
        assertThat(school.teacher().get(student).status()).as("the teacher of an offering they are in").isEqualTo(200);
        assertThat(elsewhere.teacher().get(student).status()).isEqualTo(404);
        assertThat(school.secondStudent().get(student).status()).isEqualTo(404);
        assertThat(school.inspector().get(student).status()).as("STUDENT_PROFILE_READ_ANY").isEqualTo(200);
        assertThat(school.student().get(student).status()).as("one's own").isEqualTo(200);

        Answer faculty = school.dean().get(API + "/faculty");
        check("GET", API + "/faculty", 200, faculty.json());
        assertThat(faculty.json().get("faculty").get("code").asString()).isEqualTo(school.faculty());
        assertThat(faculty.json().get("students").size()).isEqualTo(2);
        assertThat(faculty.json().get("offerings").get(0).get("code").asString()).isEqualTo(school.course());
    }

    @Test
    void permissionsDecideNotTheNavigation() throws Exception {
        School school = newSchool();
        School other = newSchool();
        long offering = school.offering(30);
        school.student().post(API + "/courses/" + offering + "/enroll", null);
        long enrollment = find(school.student().get(API + "/enrollments").json(), "code", school.course()).get("id").asLong();

        for (String path : List.of("/admin/staff", "/admin/modules", "/inspect/students", "/inspect/courses", "/faculty",
                "/teach/offerings")) {
            Answer refused = school.student().get(API + path);
            assertThat(refused.status()).as(path).isEqualTo(403);
            assertThat(refused.json().get("error").asString()).isEqualTo("forbidden");
            assertThat(refused.header(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        }
        assertThat(school.student().post(API + "/inspect/courses",
                "{\"code\": \"X1\", \"title\": \"X\", \"ects_credits\": 5, \"faculty_code\": \"FCST\"}").status())
                .isEqualTo(403);
        assertThat(other.teacher().get(API + "/teach/offerings/" + offering).status()).as("another teacher's roster")
                .isEqualTo(404);
        assertThat(other.teacher().post(API + "/teach/enrollments/" + enrollment + "/reject", null).status())
                .as("another teacher's enrollment").isEqualTo(409);
    }

    @Test
    void anInspectorCreatesStudentsWhoMustChangeTheFirstPassword() throws Exception {
        School school = newSchool();
        String email = "made-" + UUID.randomUUID().toString().substring(0, 8) + "@test.tu-sofia.bg";
        String body = """
                {"email": "%s", "first_name": "Нов", "last_name": "Студент", "faculty_number": "%s",
                 "specialty_id": %d, "study_year": 1, "password": "first-password"}""";

        Answer created = school.inspector().post(API + "/inspect/students",
                body.formatted(email, number(), school.specialtyId()));
        assertThat(created.status()).isEqualTo(201);
        check("POST", API + "/inspect/students", 201, created.json());

        PortalBrowser newcomer = new PortalBrowser(mockMvc);
        assertThat(newcomer.signIn(email, "first-password").getResponse().getRedirectedUrl()).isEqualTo("/password");

        Answer duplicate = school.inspector().post(API + "/inspect/students", body.formatted(email, number(), school.specialtyId()));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().get("message").asString()).isEqualTo("Имейлът вече се използва.");
        Answer badSpecialty = school.inspector().post(API + "/inspect/students",
                body.formatted("x" + email, number(), 999_999));
        assertThat(badSpecialty.status()).isEqualTo(400);
        assertThat(badSpecialty.json().get("message").asString()).isEqualTo("Непозната специалност.");

        Answer list = school.inspector().get(API + "/inspect/students?q=" + email);
        check("GET", API + "/inspect/students", 200, list.json());
        assertThat(list.json().size()).isEqualTo(1);
        assertThat(school.inspector().put(API + "/inspect/students/" + list.json().get(0).get("sub").asString() + "/status",
                "{\"status\": \"ASLEEP\"}").status()).isEqualTo(400);
        assertThat(school.inspector().put(API + "/inspect/students/999999999/status", "{\"status\": \"ACTIVE\"}").status())
                .isEqualTo(404);

        Answer catalogue = school.inspector().get(API + "/inspect/courses");
        check("GET", API + "/inspect/courses", 200, catalogue.json());
    }

    @Test
    void anAdminManagesStaffAndModules() throws Exception {
        School school = newSchool();
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 8) + "@test.tu-sofia.bg";

        Answer created = school.admin().post(API + "/admin/staff", """
                {"email": "%s", "first_name": "Нов", "last_name": "Преподавател", "role": "TEACHER",
                 "faculty_code": "%s", "password": "first-password"}""".formatted(email, school.faculty()));
        assertThat(created.status()).isEqualTo(201);
        check("POST", API + "/admin/staff", 201, created.json());
        assertThat(school.admin().post(API + "/admin/staff", """
                {"email": "x%s", "first_name": "А", "last_name": "Б", "role": "STUDENT", "password": "first-password"}"""
                .formatted(email)).status()).as("students come from the inspectors").isEqualTo(400);

        // STAFF_CREATE without the admin role: staff yes, admins no
        PortalBrowser helper = school.userWith("INSPECTOR", "[\"STAFF_CREATE\"]");
        assertThat(helper.post(API + "/admin/staff", """
                {"email": "y%s", "first_name": "А", "last_name": "Б", "role": "ADMIN", "password": "first-password"}"""
                .formatted(email)).status()).isEqualTo(403);

        Answer staff = school.admin().get(API + "/admin/staff");
        check("GET", API + "/admin/staff", 200, staff.json());
        String sub = find(staff.json(), "email", email).get("sub").asString();
        Answer toggled = school.admin().post(API + "/admin/staff/" + sub + "/toggle", null);
        check("POST", API + "/admin/staff/{sub}/toggle", 200, toggled.json());
        assertThat(toggled.json().get("enabled").asBoolean()).isFalse();
        assertThat(school.admin().post(API + "/admin/staff/" + school.adminId() + "/toggle", null).status())
                .as("not one's own account").isEqualTo(400);

        String clientId = "portal-" + UUID.randomUUID().toString().substring(0, 8);
        Answer registered = school.admin().post(API + "/admin/modules", """
                {"clientId": "%s", "name": "Портален тест", "redirectUris": ["http://localhost:3000/auth/callback"],
                 "directory": true}""".formatted(clientId));
        assertThat(registered.status()).isEqualTo(201);
        check("POST", API + "/admin/modules", 201, registered.json());
        assertThat(registered.header(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(registered.json().get("clientSecret").asString()).hasSize(43);
        Answer modules = school.admin().get(API + "/admin/modules");
        check("GET", API + "/admin/modules", 200, modules.json());
        assertThat(find(modules.json(), "client_id", clientId).get("directory").asBoolean()).isTrue();
        assertThat(helper.get(API + "/admin/modules").status()).as("modules are the admin role's").isEqualTo(403);
    }

    // ---- a faculty with one person of every role ----

    private final class School {
        private final String faculty;
        private final int specialtyId;
        private final String course;
        private final long studentId;
        private final long secondStudentId;
        private final long teacherId;
        private final long adminId;
        private final PortalBrowser student;
        private final PortalBrowser secondStudent;
        private final PortalBrowser teacher;
        private final PortalBrowser dean;
        private final PortalBrowser inspector;
        private final PortalBrowser admin;

        School() throws Exception {
            faculty = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(Locale.ROOT);
            jdbc.update("INSERT INTO faculties (code, name) VALUES (?, ?)", faculty, "Портален факултет " + faculty);
            jdbc.update("INSERT INTO specialties (faculty_code, name) VALUES (?, ?)", faculty, specialtyName());
            specialtyId = jdbc.queryForObject("SELECT id FROM specialties WHERE faculty_code = ?", Integer.class, faculty);
            course = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);

            String studentEmail = staging("STUDENT", null, null, true);
            String secondEmail = staging("STUDENT", null, null, true);
            String teacherEmail = staging("TEACHER", faculty, null, false);
            String adminEmail = staging("ADMIN", null, null, false);
            studentId = id(studentEmail);
            secondStudentId = id(secondEmail);
            teacherId = id(teacherEmail);
            adminId = id(adminEmail);
            student = PortalBrowser.signedIn(mockMvc, studentEmail, PASSWORD);
            secondStudent = PortalBrowser.signedIn(mockMvc, secondEmail, PASSWORD);
            teacher = PortalBrowser.signedIn(mockMvc, teacherEmail, PASSWORD);
            dean = PortalBrowser.signedIn(mockMvc, staging("DEAN", faculty, null, false), PASSWORD);
            inspector = PortalBrowser.signedIn(mockMvc, staging("INSPECTOR", faculty, null, false), PASSWORD);
            admin = PortalBrowser.signedIn(mockMvc, adminEmail, PASSWORD);
            assertThat(inspector.post(API + "/inspect/courses", """
                    {"code": "%s", "title": "Тестов курс", "ects_credits": 5, "faculty_code": "%s"}"""
                    .formatted(course, faculty)).status()).isEqualTo(201);
        }

        /** A new offering of the school's course in the current semester; returns its id. */
        long offering(int capacity) throws Exception {
            jdbc.update("DELETE FROM course_offerings WHERE course_id = (SELECT id FROM courses WHERE code = ?) AND semester = ?"
                    + " AND id NOT IN (SELECT offering_id FROM enrollments)", course, SEMESTER);
            assertThat(inspector.post(API + "/inspect/offerings", """
                    {"course_code": "%s", "semester": "%s", "teacher_sub": "%d", "capacity": %d}"""
                    .formatted(course, SEMESTER, teacherId, capacity)).status()).isEqualTo(201);
            return jdbc.queryForObject("""
                    SELECT o.id FROM course_offerings o JOIN courses c ON c.id = o.course_id
                    WHERE c.code = ? AND o.semester = ?""", Long.class, course, SEMESTER);
        }

        PortalBrowser userWith(String role, String permissions) throws Exception {
            return PortalBrowser.signedIn(mockMvc, staging(role, faculty, permissions, false), PASSWORD);
        }

        String faculty() { return faculty; }
        int specialtyId() { return specialtyId; }
        String specialtyName() { return "Портална специалност " + faculty; }
        String course() { return course; }
        long studentId() { return studentId; }
        long secondStudentId() { return secondStudentId; }
        long teacherId() { return teacherId; }
        long adminId() { return adminId; }
        PortalBrowser student() { return student; }
        PortalBrowser secondStudent() { return secondStudent; }
        PortalBrowser teacher() { return teacher; }
        PortalBrowser dean() { return dean; }
        PortalBrowser inspector() { return inspector; }
        PortalBrowser admin() { return admin; }

        private String staging(String role, String facultyCode, String permissions, boolean record) throws Exception {
            String email = role.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.tu-sofia.bg";
            StringBuilder body = new StringBuilder("""
                    {"email": "%s", "password": "%s", "firstName": "Тест", "lastName": "%s", "role": "%s\""""
                    .formatted(email, PASSWORD, role, role));
            if (facultyCode != null) {
                body.append(", \"faculty\": \"").append(facultyCode).append('"');
            }
            if (permissions != null) {
                body.append(", \"permissions\": ").append(permissions);
            }
            if (record) {
                body.append(", \"student\": {\"facultyNumber\": \"").append(number())
                        .append("\", \"specialty\": \"").append(specialtyId).append("\", \"studyYear\": 2}");
            }
            body.append('}');
            assertThat(mockMvc.perform(post("/api/v1/staging/users").contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString())).andReturn().getResponse().getStatus()).as("staging user " + role).isEqualTo(201);
            return email;
        }
    }

    private School newSchool() throws Exception {
        return new School();
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static List<String> nav(PortalBrowser browser) throws Exception {
        List<String> paths = new ArrayList<>();
        for (JsonNode item : browser.get(API + "/me").json().get("nav")) {
            paths.add(item.get("path").asString());
        }
        return paths;
    }

    private static String outcome(PortalBrowser browser, long offering) throws Exception {
        Answer answer = browser.post(API + "/courses/" + offering + "/enroll", null);
        assertThat(answer.status()).isEqualTo(409);
        check("POST", API + "/courses/{id}/enroll", 409, answer.json());
        return answer.json().get("outcome").asString();
    }

    private static JsonNode find(JsonNode list, String field, Object value) {
        for (JsonNode item : list) {
            if (String.valueOf(value).equals(item.get(field).asString())) {
                return item;
            }
        }
        throw new AssertionError("no item with " + field + " = " + value + " in " + list);
    }

    private static String number() {
        return "N" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
