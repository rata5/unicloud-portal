package bg.tusofia.unicloud.portal.sso.application;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.academic.domain.Faculty;
import bg.tusofia.unicloud.portal.student.domain.Student;
import bg.tusofia.unicloud.portal.student.domain.StudentRepository;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a module learns about the user, in the ID token and from /userinfo (docs/sso.md, "User
 * claims"). Each group of claims comes only with the scope that names it, so a module receives
 * only what it asked for:
 * <ul>
 *   <li>{@code profile}: given_name, family_name, name</li>
 *   <li>{@code email}: email</li>
 *   <li>{@code unicloud}: role, permissions, faculty (for everyone who has one: teachers,
 *       deans, inspectors, and students through their specialty) and, for students, student</li>
 * </ul>
 *
 * <p>Only plain {@link String}, {@link Integer}, {@link ArrayList} and {@link LinkedHashMap}
 * values: the authorization server stores the claims as JSON and accepts only those types back.
 */
@Service
public class SsoUserClaims {

    /** The scope for the portal's own claims: role, permissions, faculty, student record. */
    public static final String UNICLOUD_SCOPE = "unicloud";

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final AcademicStructure academicStructure;

    public SsoUserClaims(
            UserRepository userRepository,
            StudentRepository studentRepository,
            AcademicStructure academicStructure) {

        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.academicStructure = academicStructure;
    }

    /**
     * The claims the granted {@code scopes} allow, for the user whose id is {@code subject}. Empty
     * when that user does not exist or is disabled. {@code sub} itself is not included: the token
     * already carries it.
     */
    public Optional<Map<String, Object>> forSubject(String subject, Collection<String> scopes) {
        return parseId(subject)
                .flatMap(userRepository::findById)
                .filter(User::isEnabled)
                .map(user -> claims(user, scopes));
    }

    /**
     * A user's profile, in the claim shapes and in a fixed order: {@code sub, role, given_name,
     * family_name, name, email}, and {@code faculty} and {@code student} when the user has them.
     * Empty for an unknown or disabled user. The directory and the portal app answer with it.
     */
    public Optional<Map<String, Object>> profile(String subject) {
        return forSubject(subject, List.of(OidcScopes.EMAIL, OidcScopes.PROFILE, UNICLOUD_SCOPE)).map(claims -> {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("sub", subject);
            for (String name : List.of("role", "given_name", "family_name", "name", "email", "faculty", "student")) {
                if (claims.containsKey(name)) {
                    profile.put(name, claims.get(name));
                }
            }
            return profile;
        });
    }

    private Map<String, Object> claims(User user, Collection<String> scopes) {
        Map<String, Object> claims = new LinkedHashMap<>();
        if (scopes.contains(OidcScopes.EMAIL)) {
            claims.put("email", user.getEmail());
        }
        if (scopes.contains(OidcScopes.PROFILE)) {
            claims.put("given_name", user.getFirstName());
            claims.put("family_name", user.getLastName());
            claims.put("name", user.getFirstName() + " " + user.getLastName());
        }
        if (scopes.contains(UNICLOUD_SCOPE)) {
            claims.put("role", user.getRole().name());
            claims.put("permissions", permissions(user));
            Optional<Student> student = studentRepository.findById(user.getId());
            Optional<Faculty> faculty = user.getFacultyCode() != null
                    ? academicStructure.faculty(user.getFacultyCode())
                    : student.flatMap(academicStructure::facultyOf);
            faculty.ifPresent(f -> claims.put("faculty", faculty(f)));
            student.ifPresent(s -> claims.put("student", student(s)));
        }
        return claims;
    }

    private static List<String> permissions(User user) {
        List<String> names = new ArrayList<>();
        for (Permission permission : Permission.fromMask(user.getPermissionsMask())) {
            names.add(permission.name());
        }
        return names;
    }

    private static Map<String, Object> faculty(Faculty faculty) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("code", faculty.code());
        claims.put("name", faculty.name());
        return claims;
    }

    private static Map<String, Object> student(Student student) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("faculty_number", student.getFacultyNumber());
        claims.put("specialty", student.getSpecialty());
        claims.put("study_year", student.getStudyYear());
        claims.put("status", student.getStatus().name());
        return claims;
    }

    private static Optional<Long> parseId(String subject) {
        try {
            return Optional.of(Long.parseLong(subject));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
