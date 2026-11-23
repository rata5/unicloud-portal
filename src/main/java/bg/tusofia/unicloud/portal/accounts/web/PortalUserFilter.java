package bg.tusofia.unicloud.portal.accounts.web;

import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.user.domain.Permission;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the signed-in user from the database on every request of the cookie session, in the
 * portal chain and in the SSO chain (accounts-and-sign-in spec):
 * <ul>
 *   <li>a user disabled or deleted since sign-in is signed out at once: the session ends and the
 *       request goes on as anonymous;</li>
 *   <li>the request's authorities are the user's permissions now, so a change of permissions
 *       counts at the next request (they are not saved back to the session);</li>
 *   <li>a user who must change the first password gets only the password page, sign-out and
 *       static files: the API answers 403 {@code password_change_required}, pages and
 *       /oauth2/authorize go to /password, and a waiting SSO login is kept for after it.</li>
 * </ul>
 * Only sessions made by a sign-in count; Bearer tokens (at /userinfo, on the old API) are left
 * alone.
 */
public final class PortalUserFilter extends OncePerRequestFilter {

    private static final String AUTHORIZE_PATH = "/oauth2/authorize";

    private final UserRepository users;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public PortalUserFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof UsernamePasswordAuthenticationToken) || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<User> found = userId(authentication.getName()).flatMap(users::findById).filter(User::isEnabled);
        if (found.isEmpty()) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            chain.doFilter(request, response);
            return;
        }

        User user = found.get();
        request.setAttribute(PortalUser.ATTRIBUTE, PortalUser.of(user));
        SecurityContext fresh = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        fresh.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                authentication.getName(), null, authoritiesNow(user, authentication)));
        SecurityContextHolder.getContextHolderStrategy().setContext(fresh);

        if (user.mustChangePassword() && !allowedBeforeThePasswordChange(request.getRequestURI())) {
            if (PortalApiErrors.isApi(request)) {
                PortalApiErrors.write(response, HttpServletResponse.SC_FORBIDDEN,
                        "password_change_required", PortalApiErrors.CHANGE_PASSWORD);
                return;
            }
            if (AUTHORIZE_PATH.equals(request.getRequestURI()) && "GET".equals(request.getMethod())) {
                requestCache.saveRequest(request, response);
            }
            response.sendRedirect("/password");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The role, the permissions as they are now, and the password factor of the sign-in. */
    private static List<GrantedAuthority> authoritiesNow(User user, Authentication signedIn) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        for (Permission permission : Permission.fromMask(user.getPermissionsMask())) {
            authorities.add(new SimpleGrantedAuthority(permission.name()));
        }
        for (GrantedAuthority authority : signedIn.getAuthorities()) {
            if (authority instanceof FactorGrantedAuthority) {
                authorities.add(authority);
            }
        }
        return authorities;
    }

    private static boolean allowedBeforeThePasswordChange(String path) {
        return path.equals("/password") || path.equals("/logout") || path.equals("/login")
                || path.equals("/connect/logout") || path.equals("/error")
                || path.startsWith("/ui/v1/") || path.startsWith("/css/");
    }

    private static Optional<Long> userId(String name) {
        try {
            return Optional.of(Long.parseLong(name));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
