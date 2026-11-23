package bg.tusofia.unicloud.portal.user.domain;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

public class User {
    private Long id;
    private String email;
    private String passwordHash;
    private String firstName;
    private String lastName;
    private Role role;
    private Long permissionsMask;
    // The faculty a teacher, dean or inspector belongs to; null for admins and students
    private String facultyCode;
    boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private User(
            Long id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String role,
            Long permissionsMask,
            String facultyCode,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        this.id = id;
        this.email = normalizeEmail(email);
        this.passwordHash = requireNonBlank(passwordHash, "Password hash");
        this.firstName = requireNonBlank(firstName, "First name");
        this.lastName = requireNonBlank(lastName, "Last name");
        this.role = Role.valueOf(Objects.requireNonNull(role, "Role is required"));
        // A new user starts with the role's default permissions; an existing user keeps the
        // mask stored for them (grants and revocations must survive a reload).
        this.permissionsMask = permissionsMask != null
                ? permissionsMask
                : this.role.getDefaultPermissions();
        this.facultyCode = facultyCode == null || facultyCode.isBlank()
                ? null
                : facultyCode.strip().toUpperCase(Locale.ROOT);
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }



    /**
     * A new user with the role's default permissions. Teachers, deans and inspectors need a
     * faculty; admins and students must not have one (a student's faculty comes from the
     * specialty). Whether the faculty exists is the caller's check.
     */
    public static User createNew(String email, String passwordHash, String firstName, String lastName,
                                 String role, String facultyCode) {

        User user = new User(null,
                email,
                passwordHash,
                firstName,
                lastName,
                role,
                null,
                facultyCode,
                true,
                null,
                null);
        if (user.role.belongsToAFaculty() && user.facultyCode == null) {
            throw new IllegalArgumentException("faculty: a " + user.role + " belongs to a faculty, give its code");
        }
        if (!user.role.belongsToAFaculty() && user.facultyCode != null) {
            throw new IllegalArgumentException(
                    "faculty: only a TEACHER, DEAN or INSPECTOR belongs to a faculty directly");
        }
        return user;
    }

    /** A stored user without a faculty (admins, students, and tests that do not need one). */
    public static User reconstitute(
            Long id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String role,
            Long permissionsMask,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        return reconstitute(id, email, passwordHash, firstName, lastName, role, permissionsMask,
                null, enabled, createdAt, updatedAt);
    }

    /**
     * A stored user, as saved. No faculty rule is checked here: rows written before V5, or by an
     * older replica during a deploy, may lack the faculty, and must still load.
     */
    public static User reconstitute(
            Long id,
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String role,
            Long permissionsMask,
            String facultyCode,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        Objects.requireNonNull(id, "Existing user must have an ID");
        Objects.requireNonNull(createdAt, "Creation timestamp is required");
        Objects.requireNonNull(updatedAt, "Update timestamp is required");

        return new User(
                id,
                email,
                passwordHash,
                firstName,
                lastName,
                role,
                permissionsMask,
                facultyCode,
                enabled,
                createdAt,
                updatedAt
        );
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    private static String normalizeEmail(String email) {
        return requireNonBlank(email, "Email")
                .toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Role getRole() {
        return role;
    }

    public boolean hasPermission(Permission permission) {
        return permission.isGranted(permissionsMask);
    }

    public void grantPermission(Permission permission) {
        permissionsMask |= permission.getMask();
    }

    public void revokePermission(Permission permission) {
        permissionsMask &= ~permission.getMask();
    }

    public long getPermissionsMask() {
        return permissionsMask;
    }

    /** The faculty code of a teacher, dean or inspector, or null. */
    public String getFacultyCode() {
        return facultyCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
