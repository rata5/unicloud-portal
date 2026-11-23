package bg.tusofia.unicloud.portal.staging.dto;

import java.util.List;

/** A user as the staging tools show it. Never the password. */
public record StagingUserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        // The user's faculty code (for a student, the specialty's faculty), or null
        String faculty,
        List<String> permissions,
        boolean student
) {
}
