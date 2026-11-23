package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.accounts.application.AccountCreation;
import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.sso.application.SsoClientRegistrationService;
import bg.tusofia.unicloud.portal.sso.controller.dto.RegisterSsoClientRequest;
import bg.tusofia.unicloud.portal.sso.controller.dto.RegisterSsoClientResponse;
import bg.tusofia.unicloud.portal.sso.infrastructure.AccessTokenDecoders;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import bg.tusofia.unicloud.portal.user.domain.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Administration: staff accounts and module registration (portal-api.openapi.json:
 * portalStaff, portalCreateStaff, portalToggleStaff, portalModules, portalRegisterModule). Ported
 * from the fork's {@code portal_api/admin.rs}. Modules are the admin role's: the one place where
 * the specs ask for a role rather than a permission.
 */
@RestController
@RequestMapping("/api/v1/portal/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    public record NewStaff(
            String email,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            String role,
            @JsonProperty("faculty_code") String facultyCode,
            String password) {
    }

    private final JdbcTemplate jdbc;
    private final AccountCreation accounts;
    private final SsoClientRegistrationService registrations;

    public AdminController(JdbcTemplate jdbc, AccountCreation accounts, SsoClientRegistrationService registrations) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.registrations = registrations;
    }

    @GetMapping("/staff")
    public List<Map<String, Object>> staff(PortalUser user) {
        PortalApiException.require(user, Permission.STAFF_CREATE);
        return jdbc.query("""
                SELECT id, CONCAT(first_name, ' ', last_name) AS name, email, role, faculty_code, enabled
                FROM users WHERE role <> 'STUDENT'
                ORDER BY role, last_name, first_name""", (rs, row) -> {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("sub", String.valueOf(rs.getLong("id")));
            member.put("name", rs.getString("name"));
            member.put("email", rs.getString("email"));
            member.put("role", rs.getString("role"));
            member.put("faculty", rs.getString("faculty_code"));
            member.put("enabled", rs.getBoolean("enabled"));
            return member;
        });
    }

    /** A new teacher, dean, inspector or admin who must change the first password; only an admin makes an admin. */
    @PostMapping("/staff")
    public ResponseEntity<Map<String, String>> createStaff(PortalUser user, @RequestBody NewStaff form) {
        PortalApiException.require(user, Permission.STAFF_CREATE);
        Role role = parseStaffRole(form.role());
        if (role == Role.ADMIN && user.role() != Role.ADMIN) {
            throw PortalApiException.forbidden();
        }
        String faculty = form.facultyCode() == null || form.facultyCode().isBlank() ? null : form.facultyCode();
        long id = accounts.create(new AccountCreation.NewAccount(form.email(), form.password(), form.firstName(),
                form.lastName(), role, role.belongsToAFaculty() ? faculty : null, null));
        log.info("Staff member {} ({}) created by user {}", id, role, user.id());
        return ResponseEntity.status(201).body(Map.of("sub", String.valueOf(id)));
    }

    /** Enables or disables a staff account, never one's own; {@code {enabled}} is the state now. */
    @PostMapping("/staff/{sub}/toggle")
    public Map<String, Boolean> toggleStaff(PortalUser user, @PathVariable long sub) {
        PortalApiException.require(user, Permission.STAFF_CREATE);
        if (sub == user.id()) {
            throw PortalApiException.invalid("Не можете да спрете собствения си профил.");
        }
        if (jdbc.update("UPDATE users SET enabled = NOT enabled WHERE id = ? AND role <> 'STUDENT'", sub) == 0) {
            throw PortalApiException.notFound();
        }
        Boolean enabled = jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, sub);
        log.info("Staff member {} enabled={}, by user {}", sub, enabled, user.id());
        return Map.of("enabled", Boolean.TRUE.equals(enabled));
    }

    @GetMapping("/modules")
    public List<Map<String, Object>> modules(PortalUser user) {
        requireAdmin(user);
        return jdbc.query("""
                SELECT client_id, client_name, redirect_uris, scopes, client_id_issued_at
                FROM oauth2_registered_client ORDER BY client_id_issued_at DESC""", (rs, row) -> {
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("client_id", rs.getString("client_id"));
            module.put("name", rs.getString("client_name"));
            module.put("redirect_uris", list(rs.getString("redirect_uris")));
            module.put("directory", list(rs.getString("scopes")).contains(AccessTokenDecoders.DIRECTORY_SCOPE));
            Timestamp issued = rs.getTimestamp("client_id_issued_at");
            module.put("created", issued == null ? "" : issued.toInstant().atOffset(ZoneOffset.UTC).toLocalDate().toString());
            return module;
        });
    }

    /** The answer holds the secret once, and is never cached. */
    @PostMapping("/modules")
    public ResponseEntity<Map<String, Object>> registerModule(PortalUser user,
                                                              @Valid @RequestBody RegisterSsoClientRequest form) {
        requireAdmin(user);
        RegisterSsoClientResponse registered = registrations.register(form, "user:" + user.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", registered.clientId());
        body.put("clientSecret", registered.clientSecret());
        body.put("redirectUris", registered.redirectUris());
        body.put("postLogoutRedirectUris", registered.postLogoutRedirectUris());
        body.put("directory", Boolean.TRUE.equals(form.directory()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    private static void requireAdmin(PortalUser user) {
        if (user.role() != Role.ADMIN) {
            throw PortalApiException.forbidden();
        }
    }

    private static Role parseStaffRole(String value) {
        try {
            Role role = Role.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
            if (role != Role.STUDENT) {
                return role;
            }
        } catch (IllegalArgumentException e) {
            // falls through to the refusal
        }
        throw PortalApiException.invalid("Невалидна роля.");
    }

    private static List<String> list(String commaSeparated) {
        return commaSeparated == null || commaSeparated.isBlank() ? List.of()
                : Arrays.stream(commaSeparated.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
