package bg.tusofia.unicloud.portal.enrollment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Enrolling, cancelling and rejecting (enrollment spec), for the app's JSON API. Ported from the
 * fork's {@code enrollment.rs}, on this portal's way of counting places (PR #14): the offering's
 * row is locked ({@code SELECT ... FOR UPDATE}) and the ENROLLED rows are counted under READ
 * COMMITTED, so the rush at 08:00 cannot oversell, on MySQL and on PostgreSQL alike.
 */
@Service
public class EnrollmentRules {

    /** What an enrollment request came to; the names are the contract's {@code outcome}. */
    public enum Outcome {
        ENROLLED("Записахте се успешно."),
        ALREADY_ENROLLED("Вече сте записани в този курс."),
        FULL("Няма свободни места."),
        CLOSED("Записването за този курс е затворено."),
        REJECTED("Преподавателят е отхвърлил записването ви в този курс."),
        NOT_ACTIVE("Само действащи студенти могат да се записват."),
        NO_SUCH_OFFERING("Курсът не съществува.");

        private final String message;

        Outcome(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final JdbcTemplate jdbc;
    private final String currentSemester;

    public EnrollmentRules(JdbcTemplate jdbc, @Value("${app.current-semester}") String currentSemester) {
        this.jdbc = jdbc;
        this.currentSemester = currentSemester.strip();
    }

    public String currentSemester() {
        return currentSemester;
    }

    /**
     * A student's request for a place. Only an ACTIVE student, only an open offering of the
     * current semester, never twice, never after a rejection, and only while a place is free.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome enroll(long studentId, long offeringId) {
        if (!isActiveStudent(studentId)) {
            return Outcome.NOT_ACTIVE;
        }
        // Locks the offering: requests for it take turns from here to the commit
        List<Map<String, Object>> offering = jdbc.queryForList(
                "SELECT semester, capacity, is_open FROM course_offerings WHERE id = ? FOR UPDATE", offeringId);
        if (offering.isEmpty()) {
            return Outcome.NO_SUCH_OFFERING;
        }
        List<String> existing = jdbc.queryForList(
                "SELECT status FROM enrollments WHERE student_id = ? AND offering_id = ?", String.class,
                studentId, offeringId);
        Map<String, Object> row = offering.getFirst();
        Outcome refused = refusal(true, existing.isEmpty() ? null : existing.getFirst(),
                Boolean.TRUE.equals(asBoolean(row.get("is_open"))), (String) row.get("semester"),
                taken(offeringId), ((Number) row.get("capacity")).intValue());
        if (refused != null) {
            return refused;
        }
        try {
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO enrollments (student_id, offering_id, status) VALUES (?, ?, 'ENROLLED')",
                        studentId, offeringId);
            } else {
                // A cancelled request comes back: the same record, ENROLLED again
                jdbc.update("""
                        UPDATE enrollments SET status = 'ENROLLED', decided_by = NULL
                        WHERE student_id = ? AND offering_id = ?""", studentId, offeringId);
            }
        } catch (DataIntegrityViolationException e) {
            // The same student's parallel first request won (unique student and offering)
            return Outcome.ALREADY_ENROLLED;
        }
        return Outcome.ENROLLED;
    }

    /**
     * The rule for a request, given what is known: why it would be refused, or null when it would
     * be accepted. {@link #enroll} applies it under the offering's lock; the catalogue uses it to
     * offer the enroll form only where it would succeed (react-hypermedia-ui spec).
     *
     * @param existingStatus the student's enrollment in the offering, or null
     */
    public Outcome refusal(boolean studentActive, String existingStatus, boolean open, String semester,
                           int taken, int capacity) {
        if (!studentActive) {
            return Outcome.NOT_ACTIVE;
        }
        if ("ENROLLED".equals(existingStatus)) {
            return Outcome.ALREADY_ENROLLED;
        }
        if ("REJECTED".equals(existingStatus)) {
            return Outcome.REJECTED;
        }
        if (!open || !currentSemester.equals(semester)) {
            return Outcome.CLOSED;
        }
        if (taken >= capacity) {
            return Outcome.FULL;
        }
        return null;
    }

    /** Whether the student may request places at all: an ACTIVE academic record. */
    public boolean isActiveStudent(long studentId) {
        List<String> status = jdbc.queryForList("SELECT status FROM students WHERE user_id = ?", String.class, studentId);
        return !status.isEmpty() && "ACTIVE".equals(status.getFirst());
    }

    /** The student cancels their own ENROLLED enrollment; false when there is none to cancel. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean cancel(long studentId, long enrollmentId) {
        if (!lockOfferingOf(enrollmentId)) {
            return false;
        }
        return jdbc.update("""
                UPDATE enrollments SET status = 'CANCELLED'
                WHERE id = ? AND student_id = ? AND status = 'ENROLLED'""", enrollmentId, studentId) == 1;
    }

    /**
     * The offering's own teacher rejects an ENROLLED enrollment, which frees the place; false when
     * it is not theirs or not ENROLLED.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reject(long teacherId, long enrollmentId) {
        if (!lockOfferingOf(enrollmentId)) {
            return false;
        }
        return jdbc.update("""
                UPDATE enrollments SET status = 'REJECTED', decided_by = ?
                WHERE id = ? AND status = 'ENROLLED'
                  AND offering_id IN (SELECT id FROM course_offerings WHERE teacher_id = ?)""",
                teacherId, enrollmentId, teacherId) == 1;
    }

    /** ENROLLED places taken in the offering. */
    public int taken(long offeringId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE offering_id = ? AND status = 'ENROLLED'", Integer.class, offeringId);
        return count == null ? 0 : count;
    }

    /** Takes the offering's lock, like enroll, so a change and a request never interleave. */
    private boolean lockOfferingOf(long enrollmentId) {
        List<Long> offering = jdbc.queryForList("SELECT offering_id FROM enrollments WHERE id = ?", Long.class, enrollmentId);
        if (offering.isEmpty()) {
            return false;
        }
        jdbc.queryForList("SELECT id FROM course_offerings WHERE id = ? FOR UPDATE", offering.getFirst());
        return true;
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : null;
    }
}
