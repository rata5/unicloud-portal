package bg.tusofia.unicloud.portal.directory;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.academic.domain.Faculty;
import bg.tusofia.unicloud.portal.exception.ResourceNotFoundException;
import bg.tusofia.unicloud.portal.sso.application.SsoUserClaims;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The directory API: read-only lookups for module backends, with no user present
 * (docs/sso.md, "The directory API"; module-directory spec). Only a module's client-credentials
 * token with scope {@code directory} gets here (DirectorySecurityConfig). The shapes and names
 * are those of the SSO claims.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    private static final List<String> USER_SCOPES =
            List.of(OidcScopes.EMAIL, OidcScopes.PROFILE, SsoUserClaims.UNICLOUD_SCOPE);

    private final SsoUserClaims userClaims;
    private final UserRepository users;
    private final AcademicStructure academicStructure;
    private final DirectoryQueries queries;

    public DirectoryController(
            SsoUserClaims userClaims,
            UserRepository users,
            AcademicStructure academicStructure,
            DirectoryQueries queries) {

        this.userClaims = userClaims;
        this.users = users;
        this.academicStructure = academicStructure;
        this.queries = queries;
    }

    /** {@code {sub, role, given_name, family_name, name, email, faculty, student}}. */
    @GetMapping("/users/{sub}")
    public Map<String, Object> user(@PathVariable String sub) {
        Map<String, Object> claims = userClaims.forSubject(sub, USER_SCOPES)
                .orElseThrow(() -> notFound("User", sub));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("sub", sub);
        for (String name : List.of("role", "given_name", "family_name", "name", "email", "faculty", "student")) {
            if (claims.containsKey(name)) {
                user.put(name, claims.get(name));
            }
        }
        return user;
    }

    /** A student's ENROLLED offerings: {@code [{id, code, title, semester}]}. */
    @GetMapping("/users/{sub}/enrollments")
    public List<Map<String, Object>> enrollments(@PathVariable String sub) {
        User student = enabledUser(sub, Role.STUDENT).orElseThrow(() -> notFound("Student", sub));
        return queries.enrolledOfferings(student.getId());
    }

    /** The offerings a teacher teaches: {@code [{id, code, title, semester}]}. */
    @GetMapping("/users/{sub}/offerings")
    public List<Map<String, Object>> offerings(@PathVariable String sub) {
        User teacher = enabledUser(sub, Role.TEACHER).orElseThrow(() -> notFound("Teacher", sub));
        return queries.taughtOfferings(teacher.getId());
    }

    /** The ENROLLED students of an offering: {@code [{sub, name, faculty_number}]}. */
    @GetMapping("/offerings/{id}/students")
    public List<Map<String, Object>> offeringStudents(@PathVariable long id) {
        if (!queries.offeringExists(id)) {
            throw notFound("Offering", String.valueOf(id));
        }
        return queries.enrolledStudents(id);
    }

    /** {@code [{id, code, title, semester, teacher_sub}]}, 200 per page. */
    @GetMapping("/faculties/{code}/offerings")
    public ResponseEntity<List<Map<String, Object>>> facultyOfferings(
            @PathVariable String code,
            @RequestParam(required = false) String semester,
            @RequestParam(defaultValue = "1") int page) {

        Faculty faculty = faculty(code);
        return paged(queries.facultyOfferings(faculty.code(), semester, pageNumber(page)), page);
    }

    /** {@code [{sub, name, faculty_number, specialty, study_year, status}]}, 200 per page. */
    @GetMapping("/faculties/{code}/students")
    public ResponseEntity<List<Map<String, Object>>> facultyStudents(
            @PathVariable String code,
            @RequestParam(defaultValue = "1") int page) {

        Faculty faculty = faculty(code);
        return paged(queries.facultyStudents(faculty.code(), pageNumber(page)), page);
    }

    private Optional<User> enabledUser(String sub, Role role) {
        try {
            return users.findById(Long.parseLong(sub))
                    .filter(User::isEnabled)
                    .filter(user -> user.getRole() == role);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Faculty faculty(String code) {
        return academicStructure.faculty(code).orElseThrow(() -> notFound("Faculty", code));
    }

    private static int pageNumber(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("page: must be 1 or more");
        }
        return page;
    }

    /** The page's items, with {@code Link: <...?page=n+1>; rel="next"} when more follow. */
    private static ResponseEntity<List<Map<String, Object>>> paged(DirectoryQueries.Page result, int page) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.hasNext()) {
            // The request's own query is already encoded (a semester stays as the module sent it)
            String next = ServletUriComponentsBuilder.fromCurrentRequest()
                    .replaceQueryParam("page", page + 1)
                    .build(true)
                    .toUriString();
            response.header(HttpHeaders.LINK, "<" + next + ">; rel=\"next\"");
        }
        return response.body(result.items());
    }

    private static ResourceNotFoundException notFound(String what, String id) {
        return new ResourceNotFoundException(what, "id", id);
    }
}
