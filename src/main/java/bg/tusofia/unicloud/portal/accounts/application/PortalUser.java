package bg.tusofia.unicloud.portal.accounts.application;

import bg.tusofia.unicloud.portal.user.domain.Permission;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * The signed-in user, read from the database on every request (PortalUserFilter), so a user
 * disabled or given other permissions sees the change at the next request
 * (accounts-and-sign-in spec, "Disabled users"; role-access spec).
 */
public record PortalUser(
        long id,
        String email,
        String firstName,
        String lastName,
        Role role,
        Set<Permission> permissions,
        String facultyCode,
        boolean mustChangePassword) {

    /** The request attribute that holds the signed-in user, if any. */
    public static final String ATTRIBUTE = PortalUser.class.getName();

    public static PortalUser of(User user) {
        return new PortalUser(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getRole(), Permission.fromMask(user.getPermissionsMask()), user.getFacultyCode(),
                user.mustChangePassword());
    }

    /** The signed-in user of this request, or null. */
    public static PortalUser current(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof PortalUser user ? user : null;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public String name() {
        return firstName + " " + lastName;
    }
}
