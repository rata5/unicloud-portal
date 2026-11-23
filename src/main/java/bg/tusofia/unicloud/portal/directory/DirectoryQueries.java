package bg.tusofia.unicloud.portal.directory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The directory's reads, in plain SQL that MySQL and PostgreSQL both run. Field names are the
 * wire names (snake_case, as in the SSO claims). Lists leave out disabled users.
 */
@Repository
public class DirectoryQueries {

    /** At most this many items per page; more come with a {@code next} link. */
    public static final int PAGE_SIZE = 200;

    private static final String OFFERING_COLUMNS = "o.id, c.code, c.title, o.semester";

    private final JdbcTemplate jdbc;

    public DirectoryQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> enrolledOfferings(long studentId) {
        return jdbc.query("SELECT " + OFFERING_COLUMNS + """
                 FROM enrollments e
                JOIN course_offerings o ON o.id = e.offering_id
                JOIN courses c ON c.id = o.course_id
                WHERE e.student_id = ? AND e.status = 'ENROLLED'
                ORDER BY o.semester, c.code, o.id""", DirectoryQueries::offering, studentId);
    }

    public List<Map<String, Object>> taughtOfferings(long teacherId) {
        return jdbc.query("SELECT " + OFFERING_COLUMNS + """
                 FROM course_offerings o
                JOIN courses c ON c.id = o.course_id
                WHERE o.teacher_id = ?
                ORDER BY o.semester, c.code, o.id""", DirectoryQueries::offering, teacherId);
    }

    public boolean offeringExists(long offeringId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM course_offerings WHERE id = ?",
                Integer.class, offeringId);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> enrolledStudents(long offeringId) {
        return jdbc.query("""
                SELECT u.id, u.first_name, u.last_name, s.faculty_number
                FROM enrollments e
                JOIN students s ON s.user_id = e.student_id
                JOIN users u ON u.id = s.user_id
                WHERE e.offering_id = ? AND e.status = 'ENROLLED' AND u.enabled = TRUE
                ORDER BY u.last_name, u.first_name, u.id""", (rs, row) -> {
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("sub", String.valueOf(rs.getLong("id")));
            student.put("name", rs.getString("first_name") + " " + rs.getString("last_name"));
            student.put("faculty_number", rs.getString("faculty_number"));
            return student;
        }, offeringId);
    }

    /** One page (from 1) of the offerings of the faculty's courses, optionally of one semester. */
    public Page facultyOfferings(String facultyCode, String semester, int page) {
        List<Object> args = new ArrayList<>(List.of(facultyCode));
        String bySemester = "";
        if (semester != null && !semester.isBlank()) {
            bySemester = " AND o.semester = ?";
            args.add(semester.strip());
        }
        return page("SELECT " + OFFERING_COLUMNS + ", o.teacher_id"
                + " FROM course_offerings o JOIN courses c ON c.id = o.course_id"
                + " WHERE c.faculty_code = ?" + bySemester
                + " ORDER BY o.semester, c.code, o.id", args, page, (rs, row) -> {
            Map<String, Object> offering = offering(rs, row);
            offering.put("teacher_sub", String.valueOf(rs.getLong("teacher_id")));
            return offering;
        });
    }

    /** One page (from 1) of the students whose specialty belongs to the faculty. */
    public Page facultyStudents(String facultyCode, int page) {
        return page("""
                SELECT u.id, u.first_name, u.last_name, s.faculty_number, sp.name AS specialty,
                       s.study_year, s.status
                FROM students s
                JOIN users u ON u.id = s.user_id
                JOIN specialties sp ON sp.id = s.specialty_id
                WHERE sp.faculty_code = ? AND u.enabled = TRUE
                ORDER BY u.last_name, u.first_name, u.id""", List.of(facultyCode), page, (rs, row) -> {
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("sub", String.valueOf(rs.getLong("id")));
            student.put("name", rs.getString("first_name") + " " + rs.getString("last_name"));
            student.put("faculty_number", rs.getString("faculty_number"));
            student.put("specialty", rs.getString("specialty"));
            student.put("study_year", rs.getInt("study_year"));
            student.put("status", rs.getString("status"));
            return student;
        });
    }

    /** A page of items, and whether another page follows. */
    public record Page(List<Map<String, Object>> items, boolean hasNext) {
    }

    private Page page(String sql, List<Object> args, int page, RowMapper<Map<String, Object>> mapper) {
        List<Object> all = new ArrayList<>(args);
        // One more row than a page holds tells whether there is a next page
        all.add(PAGE_SIZE + 1);
        all.add((long) (page - 1) * PAGE_SIZE);
        List<Map<String, Object>> rows = jdbc.query(sql + " LIMIT ? OFFSET ?", mapper, all.toArray());
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new Page(hasNext ? rows.subList(0, PAGE_SIZE) : rows, hasNext);
    }

    private static Map<String, Object> offering(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Map<String, Object> offering = new LinkedHashMap<>();
        offering.put("id", rs.getLong("id"));
        offering.put("code", rs.getString("code"));
        offering.put("title", rs.getString("title"));
        offering.put("semester", rs.getString("semester"));
        return offering;
    }
}
