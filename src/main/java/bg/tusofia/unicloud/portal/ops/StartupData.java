package bg.tusofia.unicloud.portal.ops;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.academic.domain.Specialty;
import bg.tusofia.unicloud.portal.student.domain.Student;
import bg.tusofia.unicloud.portal.student.domain.StudentRepository;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data the portal makes for itself at start, after Flyway (operations spec, "Start-up data"):
 * <ul>
 *   <li><b>The first admin</b>, from {@code APP_BOOTSTRAP_ADMIN_EMAIL} and
 *       {@code APP_BOOTSTRAP_ADMIN_PASSWORD}, when no admin can sign in yet. It must choose a
 *       new password at the first sign-in.</li>
 *   <li><b>Demo data</b>, with {@code APP_DEMO_PASSWORD}, only with the {@code staging} or
 *       {@code dev} profile, never in production: one user of every role, a course and an
 *       offering of the current semester. Made once.</li>
 * </ul>
 * Neither stops the start when it cannot run; it logs why.
 */
@Component
public class StartupData implements ApplicationRunner {

    /**
     * The users of migration V2 (test data, in every database) carry fake hashes starting with
     * this. Nobody can sign in with them, so they do not count as an admin.
     */
    static final String PLACEHOLDER_HASH_PREFIX = "$2a$10$dummyhashfordevonly";

    static final String DEMO_DOMAIN = "demo.tu-sofia.bg";
    static final String DEMO_COURSE = "DEMO101";

    private static final Logger log = LoggerFactory.getLogger(StartupData.class);

    private final UserRepository users;
    private final StudentRepository students;
    private final AcademicStructure academicStructure;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final String adminEmail;
    private final String adminPassword;
    private final String demoPassword;
    private final String currentSemester;

    public StartupData(
            UserRepository users,
            StudentRepository students,
            AcademicStructure academicStructure,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Environment environment,
            @Value("${app.bootstrap-admin.email:}") String adminEmail,
            @Value("${app.bootstrap-admin.password:}") String adminPassword,
            @Value("${app.demo-password:}") String demoPassword,
            @Value("${app.current-semester}") String currentSemester) {

        this.users = users;
        this.students = students;
        this.academicStructure = academicStructure;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.environment = environment;
        this.adminEmail = adminEmail.strip();
        this.adminPassword = adminPassword;
        this.demoPassword = demoPassword;
        this.currentSemester = currentSemester.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminEmail.isEmpty() || !adminPassword.isEmpty()) {
            if (adminEmail.isEmpty() || adminPassword.isEmpty()) {
                log.warn("Set both APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD, or neither: no first admin made");
            } else {
                createFirstAdminIfNone(adminEmail, adminPassword);
            }
        }
        if (!demoPassword.isEmpty()) {
            if (demoAllowed(environment)) {
                createDemoDataOnce(demoPassword);
            } else {
                log.warn("APP_DEMO_PASSWORD is ignored: demo data only with the staging or dev profile");
            }
        }
    }

    /** Demo data never in production: only with the staging or the dev profile. */
    static boolean demoAllowed(Environment environment) {
        return environment.matchesProfiles("staging | dev");
    }

    /** True when the admin was made; false when an admin who can sign in exists already. */
    public boolean createFirstAdminIfNone(String email, String password) {
        Boolean made = transaction.execute(status -> {
            Integer usable = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM users
                    WHERE role = 'ADMIN' AND enabled = TRUE AND password_hash NOT LIKE ?""",
                    Integer.class, PLACEHOLDER_HASH_PREFIX + "%");
            if (usable != null && usable > 0) {
                log.info("An admin exists already: APP_BOOTSTRAP_ADMIN_* is not needed any more");
                return false;
            }
            if (!validPassword(password)) {
                log.error("APP_BOOTSTRAP_ADMIN_PASSWORD must be 8 to 72 characters: no first admin made");
                return false;
            }
            if (users.existsByEmail(email)) {
                log.error("APP_BOOTSTRAP_ADMIN_EMAIL {} belongs to a user already: no first admin made", email);
                return false;
            }
            User admin = users.save(User.createNew(email, passwordEncoder.encode(password),
                    "Admin", "UniCloud", Role.ADMIN.name(), null));
            jdbc.update("UPDATE users SET must_change_password = TRUE WHERE id = ?", admin.getId());
            log.info("Created the first admin {} (user {}); the password must be changed at the first sign-in",
                    admin.getEmail(), admin.getId());
            return true;
        });
        return Boolean.TRUE.equals(made);
    }

    /** True when the demo data was made now; false when it exists already or cannot be made. */
    public boolean createDemoDataOnce(String password) {
        Boolean made = transaction.execute(status -> {
            if (users.existsByEmail(demoEmail(Role.ADMIN))) {
                return false;
            }
            if (!validPassword(password)) {
                log.error("APP_DEMO_PASSWORD must be 8 to 72 characters: no demo data made");
                return false;
            }
            String hash = passwordEncoder.encode(password);
            String faculty = "FCST";
            demoUser(Role.ADMIN, "Администратор", null, hash);
            demoUser(Role.INSPECTOR, "Инспектор", faculty, hash);
            User teacher = demoUser(Role.TEACHER, "Преподавател", faculty, hash);
            if (users.existsDeanOfFaculty(faculty)) {
                log.info("{} has a dean already: no demo dean", faculty);
            } else {
                demoUser(Role.DEAN, "Декан", faculty, hash);
            }
            User student = demoUser(Role.STUDENT, "Студент", null, hash);
            Specialty specialty = academicStructure.requireSpecialty("Компютърни науки", "demo specialty");
            students.save(Student.createNew(student.getId(), "DEMO" + student.getId(),
                    specialty.id(), specialty.name(), 2));

            Integer course = jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE code = ?", Integer.class, DEMO_COURSE);
            if (course == null || course == 0) {
                jdbc.update("INSERT INTO courses (code, title, ects_credits, faculty_code) VALUES (?, ?, 5, ?)",
                        DEMO_COURSE, "Демонстрационен курс", faculty);
            }
            Integer courseId = jdbc.queryForObject("SELECT id FROM courses WHERE code = ?", Integer.class, DEMO_COURSE);
            Integer offering = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM course_offerings WHERE course_id = ? AND semester = ?",
                    Integer.class, courseId, currentSemester);
            if (offering == null || offering == 0) {
                jdbc.update("""
                        INSERT INTO course_offerings (course_id, semester, teacher_id, capacity, is_open)
                        VALUES (?, ?, ?, 10, TRUE)""", courseId, currentSemester, teacher.getId());
            }
            log.info("Demo data made: users demo.<role>@{} with the APP_DEMO_PASSWORD password, course {} in {}",
                    DEMO_DOMAIN, DEMO_COURSE, currentSemester);
            return true;
        });
        return Boolean.TRUE.equals(made);
    }

    static String demoEmail(Role role) {
        return "demo." + role.name().toLowerCase(java.util.Locale.ROOT) + "@" + DEMO_DOMAIN;
    }

    private User demoUser(Role role, String lastName, String faculty, String hash) {
        return users.save(User.createNew(demoEmail(role), hash, "Демо", lastName, role.name(), faculty));
    }

    private static boolean validPassword(String password) {
        return password != null && password.length() >= 8 && password.length() <= 72;
    }
}
