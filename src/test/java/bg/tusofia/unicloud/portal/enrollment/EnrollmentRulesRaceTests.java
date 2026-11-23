package bg.tusofia.unicloud.portal.enrollment;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 08:00 rush, mixed (enrollment spec, "No overselling under load"): new students race for
 * places while enrolled students cancel and the teacher rejects, all at the same moment. The
 * offering never holds more ENROLLED students than its capacity, and no more new students get
 * in than places were freed. Runs on MySQL and on PostgreSQL in CI. Each run makes its own
 * faculty, people and offering, which stay in the database.
 */
@SpringBootTest
class EnrollmentRulesRaceTests {

    private static final int CAPACITY = 10;
    private static final int NEWCOMERS = 30;
    private static final int CANCELLING = 5;
    private static final int REJECTED = 3;

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
    }

    @Autowired
    private EnrollmentRules enrollment;

    @Autowired
    private JdbcTemplate jdbc;

    @RepeatedTest(3)
    void enrollsCancelsAndRejectsAtOnceNeverOversell() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(Locale.ROOT);
        String faculty = "R" + tag;
        jdbc.update("INSERT INTO faculties (code, name) VALUES (?, ?)", faculty, "Състезателен факултет " + tag);
        jdbc.update("INSERT INTO specialties (faculty_code, name) VALUES (?, ?)", faculty, "Състезателна специалност " + tag);
        int specialty = jdbc.queryForObject("SELECT id FROM specialties WHERE faculty_code = ?", Integer.class, faculty);
        long teacher = user(tag, "teacher", "TEACHER", faculty);
        jdbc.update("INSERT INTO courses (code, title, ects_credits, faculty_code) VALUES (?, 'Състезание', 5, ?)", "R" + tag, faculty);
        Integer course = jdbc.queryForObject("SELECT id FROM courses WHERE code = ?", Integer.class, "R" + tag);
        jdbc.update("INSERT INTO course_offerings (course_id, semester, teacher_id, capacity, is_open) VALUES (?, ?, ?, ?, TRUE)",
                course, enrollment.currentSemester(), teacher, CAPACITY);
        long offering = jdbc.queryForObject("SELECT id FROM course_offerings WHERE course_id = ?", Long.class, course);

        // The offering is full before the rush
        List<Long> enrolled = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            long student = student(tag, "in" + i, specialty);
            assertThat(enrollment.enroll(student, offering)).isEqualTo(EnrollmentRules.Outcome.ENROLLED);
            enrolled.add(student);
        }
        List<Long> newcomers = new ArrayList<>();
        for (int i = 0; i < NEWCOMERS; i++) {
            newcomers.add(student(tag, "new" + i, specialty));
        }

        List<Callable<String>> rush = new ArrayList<>();
        for (long student : newcomers) {
            rush.add(() -> "enroll:" + enrollment.enroll(student, offering));
        }
        for (int i = 0; i < CANCELLING; i++) {
            long student = enrolled.get(i);
            long id = enrollmentId(student, offering);
            rush.add(() -> "cancel:" + enrollment.cancel(student, id));
        }
        for (int i = CANCELLING; i < CANCELLING + REJECTED; i++) {
            long id = enrollmentId(enrolled.get(i), offering);
            rush.add(() -> "reject:" + enrollment.reject(teacher, id));
        }

        List<String> results = runAtOnce(rush);

        long joined = results.stream().filter("enroll:ENROLLED"::equals).count();
        long freed = results.stream().filter(r -> r.equals("cancel:true") || r.equals("reject:true")).count();
        assertThat(freed).as("every cancel and reject went through").isEqualTo(CANCELLING + REJECTED);
        assertThat(results.stream().filter(r -> r.startsWith("enroll:"))
                .allMatch(r -> r.equals("enroll:ENROLLED") || r.equals("enroll:FULL"))).isTrue();
        assertThat(joined).as("no more newcomers than places freed").isLessThanOrEqualTo(freed);

        int taken = enrollment.taken(offering);
        assertThat(taken).as("never more than the capacity").isLessThanOrEqualTo(CAPACITY);
        assertThat(taken).isEqualTo(CAPACITY - freed + joined);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE offering_id = ? AND status = 'REJECTED'",
                Integer.class, offering)).isEqualTo(REJECTED);
    }

    private List<String> runAtOnce(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private long user(String tag, String name, String role, String faculty) {
        String email = "race-" + name + "-" + tag.toLowerCase(Locale.ROOT) + "@test.tu-sofia.bg";
        jdbc.update("""
                INSERT INTO users (email, password_hash, first_name, last_name, role, permissions_mask, faculty_code, enabled)
                VALUES (?, 'not-a-hash', 'Race', ?, ?, 0, ?, TRUE)""", email, name, role, faculty);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long student(String tag, String name, int specialty) {
        long id = user(tag, name, "STUDENT", null);
        jdbc.update("""
                INSERT INTO students (user_id, faculty_number, specialty, specialty_id, study_year, status)
                VALUES (?, ?, 'Състезателна специалност', ?, 1, 'ACTIVE')""", id, "R" + tag + name, specialty);
        return id;
    }

    private long enrollmentId(long student, long offering) {
        return jdbc.queryForObject("SELECT id FROM enrollments WHERE student_id = ? AND offering_id = ?",
                Long.class, student, offering);
    }
}
