
package bg.tusofia.unicloud.portal.student.domain;

import java.util.Objects;

public class Student {

    private final Long userId;
    private final String facultyNumber;
    // The specialty's id (V5) and its name. The name is still stored too, for replicas that
    // predate V5; a later release drops it.
    private final Integer specialtyId;
    private final String specialty;
    private int studyYear;
    private StudentStatus status;

    private Student(
            Long userId,
            String facultyNumber,
            Integer specialtyId,
            String specialty,
            int studyYear,
            StudentStatus status) {

        this.userId = Objects.requireNonNull(
                userId, "User ID is required");

        this.facultyNumber = requireNonBlank(
                facultyNumber, "Faculty number");

        this.specialtyId = specialtyId;

        this.specialty = requireNonBlank(
                specialty, "Specialty");

        validateStudyYear(studyYear);
        this.studyYear = studyYear;

        this.status = Objects.requireNonNull(
                status, "Student status is required");
    }

    /** A new, active student of a known specialty. */
    public static Student createNew(
            Long userId,
            String facultyNumber,
            int specialtyId,
            String specialty,
            int studyYear) {

        return createNew(userId, facultyNumber, specialtyId, specialty, studyYear, StudentStatus.ACTIVE);
    }

    /** A new student of a known specialty with a given status (the staging tools set any). */
    public static Student createNew(
            Long userId,
            String facultyNumber,
            int specialtyId,
            String specialty,
            int studyYear,
            StudentStatus status) {

        return new Student(
                userId,
                facultyNumber,
                specialtyId,
                specialty,
                studyYear,
                status
        );
    }

    /**
     * A stored student. {@code specialtyId} may be null for a record written by a replica that
     * predates V5.
     */
    public static Student reconstitute(
            Long userId,
            String facultyNumber,
            Integer specialtyId,
            String specialty,
            int studyYear,
            StudentStatus status) {

        return new Student(
                userId,
                facultyNumber,
                specialtyId,
                specialty,
                studyYear,
                status
        );
    }

    public void interruptStudies() {
        if (status != StudentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active students can interrupt their studies");
        }

        status = StudentStatus.INTERRUPTED;
    }

    public void resumeStudies() {
        if (status != StudentStatus.INTERRUPTED) {
            throw new IllegalStateException(
                    "Only interrupted students can resume their studies");
        }

        status = StudentStatus.ACTIVE;
    }

    public void graduate() {
        if (status != StudentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active students can graduate");
        }

        status = StudentStatus.GRADUATED;
    }

    public void advanceToNextYear() {
        if (status != StudentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active students can advance to the next year");
        }

        if (studyYear >= 6) {
            throw new IllegalStateException(
                    "Student is already in the final study year");
        }

        studyYear++;
    }

    private static void validateStudyYear(int studyYear) {
        if (studyYear < 1 || studyYear > 6) {
            throw new IllegalArgumentException(
                    "Study year must be between 1 and 6");
        }
    }

    private static String requireNonBlank(
            String value, String field) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is required");
        }

        return value.trim();
    }

    public Long getUserId() {
        return userId;
    }

    public String getFacultyNumber() {
        return facultyNumber;
    }

    /** The specialty's id, or null for a record that predates V5. */
    public Integer getSpecialtyId() {
        return specialtyId;
    }

    public String getSpecialty() {
        return specialty;
    }

    public int getStudyYear() {
        return studyYear;
    }

    public StudentStatus getStatus() {
        return status;
    }
}
