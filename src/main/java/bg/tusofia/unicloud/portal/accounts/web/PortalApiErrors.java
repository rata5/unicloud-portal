package bg.tusofia.unicloud.portal.accounts.web;

import bg.tusofia.unicloud.portal.web.ErrorPages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * The errors of the app's JSON API, in the contract's shape {@code {error, message}} with a
 * Bulgarian message (react-hypermedia-ui spec, "Session, CSRF and errors"). Never a redirect:
 * the app decides where to go (sign-in on 401, the password page on password_change_required).
 */
public final class PortalApiErrors {

    public static final String SIGN_IN_AGAIN = "Влезте отново.";
    public static final String EXPIRED = "Формулярът е изтекъл. Презаредете страницата и опитайте отново.";
    public static final String NOT_ALLOWED = "Нямате право на това действие.";
    public static final String CHANGE_PASSWORD = "Сменете първоначалната си парола, за да продължите.";

    private PortalApiErrors() {
    }

    /** No session: 401 unauthenticated. */
    public static AuthenticationEntryPoint unauthenticated() {
        return (request, response, exception) -> write(response, 401, "unauthenticated", SIGN_IN_AGAIN);
    }

    /**
     * CSRF or a rule of the chain refused the request. On the API: 401 when nobody is signed in
     * (a change sent after the session ended, so the app goes to sign-in), else 403 forbidden.
     * On a server page: the error page.
     */
    public static AccessDeniedHandler accessDenied() {
        return (request, response, exception) -> {
            if (!isApi(request)) {
                if (exception instanceof CsrfException) {
                    // As on the fork: an old form (the session ended or changed) says so
                    request.setAttribute(ErrorPages.MESSAGE, EXPIRED);
                }
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            if (!signedIn()) {
                write(response, 401, "unauthenticated", SIGN_IN_AGAIN);
            } else {
                write(response, 403, "forbidden", exception instanceof CsrfException ? EXPIRED : NOT_ALLOWED);
            }
        };
    }

    public static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    public static void write(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    private static boolean signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
