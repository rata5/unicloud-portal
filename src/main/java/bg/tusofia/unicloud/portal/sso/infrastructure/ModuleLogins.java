package bg.tusofia.unicloud.portal.sso.infrastructure;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The module logins made through a browser session, so they can be ended with it
 * (accounts-and-sign-in spec, "Sign-out" and "Password change ends module logins"):
 * <ul>
 *   <li>when a module login starts at /oauth2/authorize, its authorization id is remembered in
 *       the browser's own session (the store calls {@link #remember});</li>
 *   <li>signing out (the portal's "Изход", or a module's /connect/logout) deletes those
 *       authorizations, so their access tokens stop working at /userinfo;</li>
 *   <li>a password change deletes every login of that user.</li>
 * </ul>
 * Deleting the stored authorization is what revokes: /userinfo accepts only tokens the store
 * still holds. Ported from the fork's {@code web/session.rs} (end_session) and
 * {@code accounts/handlers.rs} (password_submit).
 */
@Component
public class ModuleLogins {

    static final String SESSION_ATTRIBUTE = ModuleLogins.class.getName() + ".IDS";

    private static final Logger log = LoggerFactory.getLogger(ModuleLogins.class);

    private final JdbcTemplate jdbc;

    public ModuleLogins(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Remembers the authorization in the current request's session, if there is one. The request
     * comes from RequestContextHolder, which Spring Boot's RequestContextFilter fills before Spring
     * Security runs (its order is -105, the security chain's -100).
     */
    public void remember(String authorizationId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null) {
            return;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(stored(session));
        if (ids.add(authorizationId)) {
            // Set again: Spring Session saves an attribute when it is set, not when it changes
            session.setAttribute(SESSION_ATTRIBUTE, ids);
        }
    }

    /** Ends the module logins made through this session. */
    public void revokeThoseOf(HttpSession session) {
        if (session == null) {
            return;
        }
        List<Object[]> ids = new ArrayList<>();
        stored(session).forEach(id -> ids.add(new Object[]{id}));
        if (!ids.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM oauth2_authorization WHERE id = ?", ids);
            log.info("Sign-out ended {} module login(s)", ids.size());
        }
        session.removeAttribute(SESSION_ATTRIBUTE);
    }

    /** Ends every module login of the user (their user id is the authorization's principal). */
    public void revokeAllOf(long userId) {
        int ended = jdbc.update("""
                DELETE FROM oauth2_authorization
                WHERE principal_name = ? AND authorization_grant_type = 'authorization_code'""", String.valueOf(userId));
        if (ended > 0) {
            log.info("A password change ended {} module login(s) of user {}", ended, userId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> stored(HttpSession session) {
        return session.getAttribute(SESSION_ATTRIBUTE) instanceof Collection<?> ids ? (Collection<String>) ids : List.of();
    }
}
