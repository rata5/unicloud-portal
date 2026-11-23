package bg.tusofia.unicloud.portal.user.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterUserRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 8)
        String password,

        @NotBlank
        String firstName,

        @NotBlank
        String lastName,

        @NotBlank
        String role,

        // A faculty code (for example FCST): required for TEACHER, DEAN and INSPECTOR, absent otherwise
        @Size(max = 16)
        String faculty
) {}
