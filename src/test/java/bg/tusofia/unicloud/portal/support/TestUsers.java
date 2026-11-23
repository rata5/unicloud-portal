package bg.tusofia.unicloud.portal.support;

import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

/**
 * Users who can sign in at /login, for tests that run without the staging profile (whose tools
 * would make them otherwise). Each has a new email; all share {@link #PASSWORD}, hashed once per
 * instance. Students study Компютърни науки (FCST, V5) and are ACTIVE.
 */
public final class TestUsers {

    public static final String PASSWORD = "test-password-1";

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final String hash;

    public TestUsers(UserRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
        this.hash = passwordEncoder.encode(PASSWORD);
    }

    public record Created(long id, String email) {
        public String sub() {
            return String.valueOf(id);
        }
    }

    /** A staff member with the role's default permissions; {@code faculty} for a teacher, dean or inspector. */
    public Created staff(String role, String faculty) {
        String email = role.toLowerCase(java.util.Locale.ROOT) + "-" + shortId() + "@test.tu-sofia.bg";
        User saved = users.save(User.createNew(email, hash, "Тест", "Служител", role, faculty));
        return new Created(saved.getId(), email);
    }

    /** An active student of Компютърни науки. */
    public Created student(String firstName, String lastName, String facultyNumber, int studyYear) {
        String email = "student-" + shortId() + "@test.tu-sofia.bg";
        User saved = users.save(User.createNew(email, hash, firstName, lastName, "STUDENT", null));
        jdbc.update("""
                INSERT INTO students (user_id, faculty_number, specialty, specialty_id, study_year, status)
                VALUES (?, ?, 'Компютърни науки',
                        (SELECT MIN(id) FROM specialties WHERE faculty_code = 'FCST' AND name = 'Компютърни науки'),
                        ?, 'ACTIVE')""", saved.getId(), facultyNumber, studyYear);
        return new Created(saved.getId(), email);
    }

    /** A faculty number no other test uses. */
    public static String facultyNumber() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
