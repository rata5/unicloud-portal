package bg.tusofia.unicloud.portal.directory;

import bg.tusofia.unicloud.portal.auth.application.AccessTokenIssuer;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import bg.tusofia.unicloud.portal.user.domain.User;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The directory API and its client-credentials tokens (module-directory spec; docs/sso.md,
 * "The directory API"). Modules register through the staging tools. Every test makes its own
 * faculty, people and courses, because the local database outlives a run.
 */
@SpringBootTest
@ActiveProfiles("staging")
class DirectoryTests {

    private static final String SEMESTER = "2026-27-W";

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
    private JdbcTemplate jdbc;

    @Autowired
    private AccessTokenIssuer portalTokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    // ---- tokens ----

    @Test
    void aModuleWithTheGrantGetsADirectoryToken() throws Exception {
        Module module = register(true);

        JsonNode answer = json.readTree(mockMvc.perform(tokenRequest(module, "directory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("directory"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.id_token").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        assertThat(answer.get("expires_in").asInt()).isBetween(1700, 1800);

        String token = answer.get("access_token").asString();
        assertThat(part(token, 0).get("typ").asString()).isEqualTo("at+jwt");
        JsonNode claims = part(token, 1);
        assertThat(claims.get("sub").asString()).isEqualTo(module.clientId());
        assertThat(claims.get("aud").toString()).contains("unicloud-directory");

        // No scope asked for: the directory scope, the only one there is with this grant
        mockMvc.perform(tokenRequest(module, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("directory"));
    }

    @Test
    void otherScopesAndModulesWithoutTheGrantGetInvalidScope() throws Exception {
        Module without = register(false);
        mockMvc.perform(tokenRequest(without, "directory"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mockMvc.perform(tokenRequest(without, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));

        Module with = register(true);
        mockMvc.perform(tokenRequest(with, "openid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mockMvc.perform(tokenRequest(new Module(with.clientId(), "not-the-secret"), "directory"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void onlyADirectoryTokenOpensTheDirectory() throws Exception {
        String directoryToken = directoryToken();

        mockMvc.perform(get("/api/v1/directory/users/4"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/directory/users/4").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/directory/users/4").header(HttpHeaders.AUTHORIZATION, portalToken()))
                .andExpect(status().isUnauthorized());

        // The directory token works there, but nowhere a user's token is wanted
        mockMvc.perform(get("/api/v1/directory/users/4").header(HttpHeaders.AUTHORIZATION, bearer(directoryToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, bearer(directoryToken)))
                .andExpect(status().isUnauthorized());

        // Read-only
        mockMvc.perform(post("/api/v1/directory/users/4").header(HttpHeaders.AUTHORIZATION, bearer(directoryToken)))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- lookups ----

    @Test
    void aUserIsShownWithTheClaimShapes() throws Exception {
        String token = bearer(directoryToken());

        mockMvc.perform(get("/api/v1/directory/users/4").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("4"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.given_name").value("Александър"))
                .andExpect(jsonPath("$.name").value("Александър Стоянов"))
                .andExpect(jsonPath("$.email").value("student1@student.tu-sofia.bg"))
                .andExpect(jsonPath("$.faculty.code").value("FCST"))
                .andExpect(jsonPath("$.student.faculty_number").value("121219001"))
                .andExpect(jsonPath("$.student.study_year").value(2))
                .andExpect(jsonPath("$.permissions").doesNotExist());

        // The admin has no faculty and no record: both left out
        mockMvc.perform(get("/api/v1/directory/users/9").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.faculty").doesNotExist())
                .andExpect(jsonPath("$.student").doesNotExist());

        mockMvc.perform(get("/api/v1/directory/users/999999999").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/directory/users/someone").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    @Test
    void coursesStudentsAndTeachersOfAFaculty() throws Exception {
        String token = bearer(directoryToken());
        School school = newSchool();

        // A student's courses and a teacher's courses
        mockMvc.perform(get("/api/v1/directory/users/" + school.student1() + "/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(school.offering()))
                .andExpect(jsonPath("$[0].code").value(school.course()))
                .andExpect(jsonPath("$[0].title").value("Тестов курс"))
                .andExpect(jsonPath("$[0].semester").value(SEMESTER));
        mockMvc.perform(get("/api/v1/directory/users/" + school.student3() + "/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/directory/users/" + school.teacher() + "/offerings")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value(school.course()));
        mockMvc.perform(get("/api/v1/directory/users/" + school.student1() + "/offerings")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/directory/users/" + school.teacher() + "/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());

        // The roster: the two enrolled students, not the one who cancelled
        mockMvc.perform(get("/api/v1/directory/offerings/" + school.offering() + "/students")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].sub", hasItem(String.valueOf(school.student1()))))
                .andExpect(jsonPath("$[*].sub", not(hasItem(String.valueOf(school.student3())))))
                .andExpect(jsonPath("$[0].faculty_number").exists());
        mockMvc.perform(get("/api/v1/directory/offerings/999999999/students").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());

        // The faculty's offerings, by semester, and its students
        mockMvc.perform(get("/api/v1/directory/faculties/" + school.faculty() + "/offerings")
                        .queryParam("semester", SEMESTER)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].teacher_sub").value(String.valueOf(school.teacher())))
                .andExpect(header().doesNotExist(HttpHeaders.LINK));
        mockMvc.perform(get("/api/v1/directory/faculties/" + school.faculty() + "/offerings")
                        .queryParam("semester", "1999-00-S")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/directory/faculties/" + school.faculty() + "/students")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].specialty").value(school.specialty()))
                .andExpect(jsonPath("$[0].study_year").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/directory/faculties/XYZ/students").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());

        // A disabled user is gone from the directory
        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", school.student2());
        mockMvc.perform(get("/api/v1/directory/users/" + school.student2()).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/directory/offerings/" + school.offering() + "/students")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void longListsComeInPagesOf200() throws Exception {
        String token = bearer(directoryToken());
        String faculty = newFaculty();
        int specialty = newSpecialty(faculty);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<Object[]> users = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            users.add(new Object[]{"page-" + tag + "-" + i + "@test.tu-sofia.bg"});
        }
        jdbc.batchUpdate("""
                INSERT INTO users (email, password_hash, first_name, last_name, role, permissions_mask, enabled)
                VALUES (?, 'not-a-hash', 'Page', 'Student', 'STUDENT', 0, TRUE)""", users);
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE email LIKE ? ORDER BY id",
                Long.class, "page-" + tag + "-%");
        List<Object[]> students = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            students.add(new Object[]{ids.get(i), "P" + tag + i, specialty});
        }
        jdbc.batchUpdate("""
                INSERT INTO students (user_id, faculty_number, specialty, specialty_id, study_year, status)
                VALUES (?, ?, 'Тестова специалност', ?, 1, 'ACTIVE')""", students);

        MvcResult first = mockMvc.perform(get("/api/v1/directory/faculties/" + faculty + "/students")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(200))
                .andExpect(header().string(HttpHeaders.LINK, containsString("page=2")))
                .andExpect(header().string(HttpHeaders.LINK, containsString("rel=\"next\"")))
                .andReturn();
        assertThat(first.getResponse().getHeader(HttpHeaders.LINK)).contains("/faculties/" + faculty + "/students");

        mockMvc.perform(get("/api/v1/directory/faculties/" + faculty + "/students")
                        .queryParam("page", "2")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().doesNotExist(HttpHeaders.LINK));
    }

    // ---- helpers ----

    private record Module(String clientId, String secret) {
    }

    /** A faculty with a teacher, a course offering, two enrolled students and one who cancelled. */
    private record School(String faculty, String specialty, long teacher, String course, long offering,
                          long student1, long student2, long student3) {
    }

    private Module register(boolean directory) throws Exception {
        String clientId = "dir-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode answer = json.readTree(mockMvc.perform(post("/api/v1/staging/sso-clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId": "%s", "name": "Directory test", "directory": %s,
                                 "redirectUris": ["http://localhost:3000/auth/callback"]}
                                """.formatted(clientId, directory)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return new Module(clientId, answer.get("clientSecret").asString());
    }

    private MockHttpServletRequestBuilder tokenRequest(Module module, String scope) {
        MockHttpServletRequestBuilder request = post("/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
                        (module.clientId() + ":" + module.secret()).getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        return scope == null ? request : request.param("scope", scope);
    }

    private String directoryToken() throws Exception {
        return json.readTree(mockMvc.perform(tokenRequest(register(true), "directory"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("access_token").asString();
    }

    private String portalToken() {
        LocalDateTime now = LocalDateTime.now();
        User admin = User.reconstitute(9L, "admin@tu-sofia.bg", "hash", "Admin", "User", "ADMIN", null, true, now, now);
        return "Bearer " + portalTokens.issue(admin).value();
    }

    private School newSchool() throws Exception {
        String faculty = newFaculty();
        int specialty = newSpecialty(faculty);
        long teacher = createUser("""
                {"email": "%s", "password": "a-long-password", "firstName": "Тест", "lastName": "Преподавател",
                 "role": "TEACHER", "faculty": "%s"}""".formatted(email(), faculty));
        long student1 = createStudent(specialty);
        long student2 = createStudent(specialty);
        long student3 = createStudent(specialty);

        String course = "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO courses (code, title, ects_credits, faculty_code) VALUES (?, 'Тестов курс', 5, ?)",
                course, faculty);
        Integer courseId = jdbc.queryForObject("SELECT id FROM courses WHERE code = ?", Integer.class, course);
        jdbc.update("INSERT INTO course_offerings (course_id, semester, teacher_id, capacity, is_open) VALUES (?, ?, ?, 30, TRUE)",
                courseId, SEMESTER, teacher);
        Long offering = jdbc.queryForObject("SELECT id FROM course_offerings WHERE course_id = ? AND semester = ?",
                Long.class, courseId, SEMESTER);
        jdbc.update("INSERT INTO enrollments (student_id, offering_id, status) VALUES (?, ?, 'ENROLLED')", student1, offering);
        jdbc.update("INSERT INTO enrollments (student_id, offering_id, status) VALUES (?, ?, 'ENROLLED')", student2, offering);
        jdbc.update("INSERT INTO enrollments (student_id, offering_id, status) VALUES (?, ?, 'CANCELLED')", student3, offering);
        return new School(faculty, "Тестова специалност " + faculty, teacher, course, offering,
                student1, student2, student3);
    }

    private long createStudent(int specialty) throws Exception {
        return createUser("""
                {"email": "%s", "password": "a-long-password", "firstName": "Тест", "lastName": "Студент",
                 "role": "STUDENT", "student": {"facultyNumber": "%s", "specialty": "%d", "studyYear": 2}}"""
                .formatted(email(), "N" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), specialty));
    }

    private long createUser(String body) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private String newFaculty() {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO faculties (code, name) VALUES (?, ?)", code, "Тестов факултет " + code);
        return code;
    }

    private int newSpecialty(String faculty) {
        String name = "Тестова специалност " + faculty;
        jdbc.update("INSERT INTO specialties (faculty_code, name) VALUES (?, ?)", faculty, name);
        return jdbc.queryForObject("SELECT id FROM specialties WHERE faculty_code = ?", Integer.class, faculty);
    }

    private static String email() {
        return "dir-" + UUID.randomUUID().toString().substring(0, 8) + "@test.tu-sofia.bg";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode part(String jwt, int index) {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]));
    }
}
