package bg.tusofia.unicloud.portal.academic.domain;

/** A specialty of one faculty. A student's faculty is the faculty of the student's specialty. */
public record Specialty(Integer id, String facultyCode, String name) {
}
