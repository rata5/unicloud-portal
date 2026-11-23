package bg.tusofia.unicloud.portal.staging;

import bg.tusofia.unicloud.portal.sso.application.SsoUserClaims;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static bg.tusofia.unicloud.portal.staging.StagingToolsTests.uniqueEmail;
import static bg.tusofia.unicloud.portal.staging.StagingToolsTests.userJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faculties, specialties and the DEAN role (V5; openspec/changes/complete-java-portal, task
 * group 1), through the staging tools and the SSO claims. Runs on MySQL and on PostgreSQL.
 *
 * <p>The local database outlives a test run, and a faculty keeps its one dean, so every test
 * that makes a dean makes its own faculty first.
 */
@SpringBootTest
@ActiveProfiles("staging")
class DeanAndFacultyTests {

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
    private SsoUserClaims ssoUserClaims;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void aFacultyHasAtMostOneDean() throws Exception {
        String faculty = newFaculty();

        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "dean", faculty, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DEAN"))
                .andExpect(jsonPath("$.faculty").value(faculty))
                .andExpect(jsonPath("$.permissions[0]").value("COURSE_OFFERING_READ"))
                .andExpect(jsonPath("$.permissions[1]").value("STUDENT_PROFILE_READ_FACULTY"))
                .andExpect(jsonPath("$.permissions.length()").value(2));

        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "DEAN", faculty.toLowerCase(Locale.ROOT), null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Факултет " + faculty + " вече има декан."));

        // Another faculty still gets its own dean, and a teacher of the first faculty is fine
        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "DEAN", newFaculty(), null, null)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "TEACHER", faculty, null, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void theDatabaseRefusesASecondDeanToo() {
        String faculty = newFaculty();
        insertDean(faculty);

        assertThatThrownBy(() -> insertDean(faculty))
                .as("uq_users_dean_faculty: a generated column on MySQL, a partial index on PostgreSQL")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyTeachersDeansAndInspectorsBelongToAFacultyDirectly() throws Exception {
        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "TEACHER", null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("faculty: a TEACHER belongs to a faculty")));

        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "ADMIN", "FCST", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("only a TEACHER, DEAN or INSPECTOR")));

        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "INSPECTOR", "NOPE", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("unknown faculty 'NOPE'")));
    }

    @Test
    void aStudentsFacultyComesFromTheSpecialty() throws Exception {
        String faculty = newFaculty();
        String specialty = "Тестова специалност " + faculty;
        int specialtyId = newSpecialty(faculty, specialty);

        // By name, in another letter case
        long byName = json.readTree(mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "STUDENT", null, null,
                                studentJson(specialty.toUpperCase(Locale.ROOT)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.faculty").value(faculty))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        // By id
        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "STUDENT", null, null,
                                studentJson(String.valueOf(specialtyId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.faculty").value(faculty));

        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(uniqueEmail(), "STUDENT", null, null, studentJson("Алхимия"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("student.specialty: unknown specialty 'Алхимия'")));

        // The record keeps the specialty's own spelling, and the list shows the faculty
        assertThat(jdbc.queryForObject("SELECT specialty FROM students WHERE user_id = ?", String.class, byName))
                .isEqualTo(specialty);
        mockMvc.perform(get("/api/v1/staging/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + byName + ")].faculty").value(faculty));
    }

    @Test
    void theFacultyClaimNamesTheFaculty() throws Exception {
        String faculty = newFaculty();
        long dean = createdId(userJson(uniqueEmail(), "DEAN", faculty, null, null));
        long admin = createdId(userJson(uniqueEmail(), "ADMIN", null, null, null));
        List<String> unicloud = List.of("openid", "unicloud");

        Map<String, Object> deanClaims = ssoUserClaims.forSubject(String.valueOf(dean), unicloud).orElseThrow();
        assertThat(deanClaims.get("role")).isEqualTo("DEAN");
        assertThat(deanClaims.get("permissions"))
                .isEqualTo(List.of("COURSE_OFFERING_READ", "STUDENT_PROFILE_READ_FACULTY"));
        assertThat(deanClaims.get("faculty")).isEqualTo(Map.of("code", faculty, "name", "Тестов факултет " + faculty));

        Map<String, Object> adminClaims = ssoUserClaims.forSubject(String.valueOf(admin), unicloud).orElseThrow();
        assertThat(adminClaims).containsKey("role").doesNotContainKey("faculty");

        assertThat(ssoUserClaims.forSubject(String.valueOf(dean), List.of("openid", "email")).orElseThrow())
                .as("faculty comes only with the unicloud scope")
                .doesNotContainKey("faculty");
    }

    @Test
    void facultiesAndTheirSpecialtiesAreListed() throws Exception {
        mockMvc.perform(get("/api/v1/staging/faculties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'FCST')].name").value("Факултет по компютърни системи и технологии"))
                .andExpect(jsonPath("$[?(@.code == 'FCST')].specialties[*].name", hasItem("Компютърни науки")))
                .andExpect(jsonPath("$[?(@.code == 'FAMI')].specialties[*].name", hasItem("Информатика")));
    }

    private long createdId(String body) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** A faculty of its own for one test: codes are at most 16 characters. */
    private String newFaculty() {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO faculties (code, name) VALUES (?, ?)", code, "Тестов факултет " + code);
        return code;
    }

    private int newSpecialty(String faculty, String name) {
        jdbc.update("INSERT INTO specialties (faculty_code, name) VALUES (?, ?)", faculty, name);
        return jdbc.queryForObject("SELECT id FROM specialties WHERE faculty_code = ? AND name = ?",
                Integer.class, faculty, name);
    }

    private void insertDean(String faculty) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, first_name, last_name, role, permissions_mask,
                                   faculty_code, enabled)
                VALUES (?, 'not-a-hash', 'Test', 'Dean', 'DEAN', 0, ?, TRUE)""",
                uniqueEmail(), faculty);
    }

    private static String studentJson(String specialty) {
        String facultyNumber = "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return """
                {"facultyNumber": "%s", "specialty": "%s", "studyYear": 2}
                """.formatted(facultyNumber, specialty);
    }
}
