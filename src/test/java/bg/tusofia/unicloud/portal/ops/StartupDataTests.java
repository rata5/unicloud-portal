package bg.tusofia.unicloud.portal.ops;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import bg.tusofia.unicloud.portal.user.domain.Role;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first admin and the demo data (operations spec, "Start-up data"). Each test runs in a
 * transaction that is rolled back, so the shared database keeps no test admin or demo users.
 */
@SpringBootTest
@ActiveProfiles("staging")
@Transactional
class StartupDataTests {

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
    }

    @Autowired
    private StartupData startupData;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @Test
    void theFirstAdminIsMadeOnlyWhileNoAdminCanSignIn() {
        // Inside the rolled-back transaction: as if no admin could sign in yet. The V2 placeholder
        // admin (admin@tu-sofia.bg, a fake hash) never counts.
        jdbc.update("UPDATE users SET enabled = FALSE WHERE role = 'ADMIN' AND password_hash NOT LIKE ?",
                StartupData.PLACEHOLDER_HASH_PREFIX + "%");
        String email = "first-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@tu-sofia.bg";

        assertThat(startupData.createFirstAdminIfNone(email, "a-long-password")).isTrue();

        Map<String, Object> admin = jdbc.queryForMap(
                "SELECT role, password_hash, must_change_password FROM users WHERE email = ?", email);
        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(passwordEncoder.matches("a-long-password", (String) admin.get("password_hash"))).isTrue();
        assertThat(admin.get("must_change_password")).isIn(true, 1, 1L);

        // Now an admin can sign in: a second start makes no second one
        assertThat(startupData.createFirstAdminIfNone("another-" + email, "a-long-password")).isFalse();
    }

    @Test
    void aTooShortPasswordMakesNoAdmin() {
        jdbc.update("UPDATE users SET enabled = FALSE WHERE role = 'ADMIN'");
        String email = "short-" + UUID.randomUUID().toString().substring(0, 8) + "@tu-sofia.bg";

        assertThat(startupData.createFirstAdminIfNone(email, "short")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void demoDataIsMadeOnceWithEveryRole() {
        // A database that has demo data from an earlier run: move it aside, inside this transaction
        jdbc.update("UPDATE users SET email = CONCAT('old-', email) WHERE email LIKE ?",
                "%@" + StartupData.DEMO_DOMAIN);

        assertThat(startupData.createDemoDataOnce("demo-password-1")).isTrue();
        // The student record is saved through JPA; flush it so the plain SQL below sees it
        entityManager.flush();

        for (Role role : Role.values()) {
            String email = StartupData.demoEmail(role);
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
            if (role == Role.DEAN && count == 0) {
                // FCST has a dean already in this database: the demo makes none
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE role = 'DEAN' AND faculty_code = 'FCST'", Integer.class))
                        .isPositive();
                continue;
            }
            assertThat(count).as(email).isEqualTo(1);
            String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
            assertThat(passwordEncoder.matches("demo-password-1", hash)).as(email).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM students s JOIN users u ON u.id = s.user_id WHERE u.email = ?""",
                Integer.class, StartupData.demoEmail(Role.STUDENT))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM course_offerings o JOIN courses c ON c.id = o.course_id
                WHERE c.code = ? AND o.semester = '2026-27-W' AND o.is_open = TRUE""",
                Integer.class, StartupData.DEMO_COURSE)).isEqualTo(1);

        // Made once
        assertThat(startupData.createDemoDataOnce("demo-password-1")).isFalse();
    }

    @Test
    void demoDataNeverInProduction() {
        assertThat(StartupData.demoAllowed(new MockEnvironment())).as("no profile: production").isFalse();
        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");
        assertThat(StartupData.demoAllowed(staging)).isTrue();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(StartupData.demoAllowed(dev)).isTrue();
    }
}
