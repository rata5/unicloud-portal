package bg.tusofia.unicloud.portal;

import bg.tusofia.unicloud.portal.enrollment.EnrollmentRules;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Students racing for the last seats must never oversell an offering, on MySQL (production) or
 * PostgreSQL (staging). The pipeline runs this class on both. Each enrollment commits for real,
 * so the test removes its own rows afterwards. The mixed race, with cancels and rejections, is
 * EnrollmentRulesRaceTests.
 */
@SpringBootTest
class EnrollmentConcurrencyTests {

	/** The active students in V2__test_data.sql. */
	private static final List<Long> ACTIVE_STUDENT_IDS = List.of(4L, 5L, 6L, 8L);

	/** ivan.petrov@tu-sofia.bg in V2__test_data.sql, a teacher of FCST since V5. */
	private static final long TEACHER_ID = 1L;

	private static final int CAPACITY = 2;

	@DynamicPropertySource
	static void jwtKeys(DynamicPropertyRegistry registry) {
		TestJwtKeys.register(registry);
	}

	@Autowired
	private EnrollmentRules enrollment;

	@Autowired
	private JdbcTemplate jdbc;

	private long course;
	private long offering;

	@BeforeEach
	void createOffering() {
		// Codes are at most 16 characters and must be unique across repetitions
		String code = "RACE" + System.nanoTime() % 1_000_000_000L;
		jdbc.update("INSERT INTO courses (code, title, ects_credits, faculty_code) VALUES (?, 'Concurrency test', 1, 'FCST')", code);
		course = jdbc.queryForObject("SELECT id FROM courses WHERE code = ?", Long.class, code);
		jdbc.update("INSERT INTO course_offerings (course_id, semester, teacher_id, capacity, is_open) VALUES (?, ?, ?, ?, TRUE)",
				course, enrollment.currentSemester(), TEACHER_ID, CAPACITY);
		offering = jdbc.queryForObject("SELECT id FROM course_offerings WHERE course_id = ?", Long.class, course);
	}

	@AfterEach
	void removeOffering() {
		jdbc.update("DELETE FROM enrollments WHERE offering_id = ?", offering);
		jdbc.update("DELETE FROM course_offerings WHERE id = ?", offering);
		jdbc.update("DELETE FROM courses WHERE id = ?", course);
	}

	@RepeatedTest(5)
	void studentsRacingForTheLastSeatsNeverOversell() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(ACTIVE_STUDENT_IDS.size());
		List<Future<EnrollmentRules.Outcome>> results = new ArrayList<>();
		try {
			for (Long studentId : ACTIVE_STUDENT_IDS) {
				results.add(pool.submit(() -> {
					start.await();
					return enrollment.enroll(studentId, offering);
				}));
			}
			start.countDown();

			int enrolled = 0;
			for (Future<EnrollmentRules.Outcome> result : results) {
				EnrollmentRules.Outcome outcome = result.get(30, TimeUnit.SECONDS);
				if (outcome == EnrollmentRules.Outcome.ENROLLED) {
					enrolled++;
				} else {
					assertEquals(EnrollmentRules.Outcome.FULL, outcome, "the only other outcome");
				}
			}

			assertEquals(CAPACITY, enrolled, "successful enrollments");
			assertEquals(CAPACITY, enrollment.taken(offering), "ENROLLED rows in the database");
		} finally {
			pool.shutdownNow();
		}
	}
}
