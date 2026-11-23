package bg.tusofia.unicloud.portal.accounts.web;

import bg.tusofia.unicloud.portal.sso.infrastructure.SsoSessionMaxAgeFilter;
import bg.tusofia.unicloud.portal.user.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The one cookie session of the portal UI and single sign-on (ADR 0003): signing in, and where
 * to go after it. Ported from the fork's {@code web/session.rs} and {@code accounts/handlers.rs}.
 */
@Component
public class PortalSession {

    /** The app's start page. */
    public static final String APP_HOME = "/app/";

    private static final String AUTHORIZE_PATH = "/oauth2/authorize";

    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final CsrfTokenRepository csrfTokens;
    private final Clock clock;

    @Autowired
    public PortalSession(CsrfTokenRepository portalCsrfTokens) {
        this(portalCsrfTokens, Clock.systemUTC());
    }

    PortalSession(CsrfTokenRepository portalCsrfTokens, Clock clock) {
        this.csrfTokens = portalCsrfTokens;
        this.clock = clock;
    }

    /**
     * Signs the user in: a new session id (an id planted beforehand is worthless), the user in
     * the session for the portal and for the SSO chain, the time the password was typed (it ends
     * the session 8 hours later and becomes the ID token's auth_time), and a new CSRF token. A
     * waiting SSO login stays in the session.
     */
    public void signIn(HttpServletRequest request, HttpServletResponse response, User user) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        Instant now = clock.instant();

        // The principal's name becomes the "sub" of every module token: the user id
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                String.valueOf(user.getId()),
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_" + user.getRole().name()),
                        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                                .issuedAt(now)
                                .build())));
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        contextRepository.saveContext(context, request, response);
        session.setAttribute(SsoSessionMaxAgeFilter.LOGIN_AT, now);
        // The token of the anonymous session is dropped; the next page gets a new one
        csrfTokens.saveToken(null, request, response);
    }

    /**
     * Where a signed-in user goes: the first-password change if it is due (a waiting SSO login
     * stays saved for after it); else the waiting SSO login; else the asked-for app page; else the
     * app's start page.
     */
    public String afterSignIn(HttpServletRequest request, HttpServletResponse response, String next,
                              boolean mustChangePassword) {
        if (mustChangePassword) {
            return "/password";
        }
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null) {
            requestCache.removeRequest(request, response);
            URI uri = URI.create(saved.getRedirectUrl());
            // Only ever the authorize endpoint, so this cannot become an open redirect
            if (AUTHORIZE_PATH.equals(uri.getRawPath())) {
                return uri.getRawQuery() == null ? AUTHORIZE_PATH : AUTHORIZE_PATH + "?" + uri.getRawQuery();
            }
        }
        String app = appPath(next);
        return app != null ? app : APP_HOME;
    }

    /**
     * An in-app return path, or null (react-hypermedia-ui spec, "Return after sign-in"): it must
     * start with /app/, have no {@code //} and no backslash, and be at most 512 characters.
     */
    public static String appPath(String next) {
        if (next == null || !next.startsWith(APP_HOME) || next.contains("//") || next.contains("\\")
                || next.length() > 512) {
            return null;
        }
        return next;
    }
}
