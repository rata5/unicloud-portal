package bg.tusofia.unicloud.portal.user.domain;

/**
 * The five roles, in the order the staging API lists them. The names are published in
 * docs/sso.md (the {@code role} claim) and never change.
 */
public enum Role {

    ADMIN(
            Permission.STAFF_CREATE
    ),

    INSPECTOR(
            Permission.STUDENT_CREATE,
            Permission.STUDENT_PROFILE_READ_ANY,
            Permission.COURSE_CREATE_MODIFY,
            Permission.COURSE_OFFERING_READ,
            Permission.COURSE_OFFERING_CREATE_MODIFY
    ),

    TEACHER(
            Permission.COURSE_OFFERING_READ,
            Permission.STUDENT_PROFILE_READ_ENROLLED,
            Permission.ENROLLMENT_REQUEST_ACCEPT_REJECT
    ),

    // Leads one faculty and sees its students and offerings. At most one per faculty (V5).
    DEAN(
            Permission.STUDENT_PROFILE_READ_FACULTY,
            Permission.COURSE_OFFERING_READ
    ),

    STUDENT(
            Permission.STUDENT_PROFILE_READ_OWN,
            Permission.COURSE_OFFERING_READ,
            Permission.ENROLLMENT_REQUEST_CREATE
    );

    private final long defaultPermissions;

    Role(Permission... permissions) {
        long mask = 0;

        for (Permission permission : permissions) {
            mask |= permission.getMask();
        }

        this.defaultPermissions = mask;
    }

    public long getDefaultPermissions() {
        return defaultPermissions;
    }

    /**
     * Whether a user with this role belongs to a faculty directly: teachers, deans and
     * inspectors do. Admins belong to none, and a student's faculty is that of the specialty.
     */
    public boolean belongsToAFaculty() {
        return this == TEACHER || this == DEAN || this == INSPECTOR;
    }
}
