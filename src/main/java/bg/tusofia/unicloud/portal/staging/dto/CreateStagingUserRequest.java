package bg.tusofia.unicloud.portal.staging.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A test user for staging.
 *
 * @param role        ADMIN, INSPECTOR, TEACHER, DEAN or STUDENT, in any letter case
 * @param faculty     a faculty code such as FCST: required for TEACHER, DEAN and INSPECTOR, absent
 *                    otherwise (a student's faculty comes from the specialty)
 * @param permissions the exact permissions, by name; null gives the role's defaults, an empty
 *                    list gives none
 * @param student     the academic record, only for role STUDENT; may be null. Its specialty is a
 *                    specialty's name or id (GET /api/v1/staging/faculties lists them)
 */
public record CreateStagingUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank String role,
        @Size(max = 16) String faculty,
        List<@NotBlank String> permissions,
        @Valid StudentRecord student
) {

    public record StudentRecord(
            @NotBlank @Size(max = 20) String facultyNumber,
            @NotBlank @Size(max = 200) String specialty,
            @NotNull @Min(1) @Max(6) Integer studyYear,
            String status
    ) {
    }
}
