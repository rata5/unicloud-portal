package bg.tusofia.unicloud.portal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One log line per request, for the service levels in docs/slo.md (operations spec, "Service
 * levels are measured"): route group, method, path, status, duration and replica, as key-value
 * pairs that structured logging turns into JSON fields (logging.structured.format.console=ecs on
 * staging and production).
 *
 * <p>Never the query string, a header, a body or a cookie: codes, tokens, secrets and passwords
 * travel there. The path alone holds none of them. /health is left out: the deploy and the
 * platform poll it, and docs/slo.md does not count those requests.
 *
 * <p>Runs before Spring Security, so refused requests (401, 403) are logged too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private final String replica = Replica.name();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if ("/health".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        long started = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            String group = routeGroup(path);
            log.atInfo()
                    .addKeyValue("route_group", group)
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", path)
                    .addKeyValue("status", status)
                    .addKeyValue("duration_ms", millis)
                    .addKeyValue("replica", replica)
                    .log("{} {} {} {} {}ms", group, request.getMethod(), path, status, millis);
        }
    }

    /**
     * The SLO's route groups (docs/slo.md); everything else is "other". The /api/v1/portal paths are
     * those of the app's contract (openspec/changes/react-hypermedia-ui/portal-api.openapi.json):
     * enrolling is {@code /courses/{id}/enroll}, cancelling and rejecting are under /enrollments.
     */
    static String routeGroup(String path) {
        if (path.equals("/login") || path.equals("/password") || path.equals("/api/v1/auth/login")) {
            return "sign-in";
        }
        if (path.equals("/api/v1/users/me") || path.startsWith("/api/v1/portal/profile")
                || path.startsWith("/api/v1/portal/students/")) {
            return "profile";
        }
        if (path.contains("/enrollments") || path.contains("/enroll")) {
            return "enrollment";
        }
        if (path.startsWith("/api/v1/courses") || path.startsWith("/api/v1/offerings")
                || path.startsWith("/api/v1/portal/courses")) {
            return "catalogue";
        }
        if (path.equals("/oauth2/token")) {
            return "sso-token";
        }
        if (path.startsWith("/oauth2/") || path.startsWith("/.well-known/") || path.equals("/userinfo")
                || path.startsWith("/connect/")) {
            return "sso";
        }
        if (path.startsWith("/api/v1/directory/")) {
            return "directory";
        }
        if (path.equals("/staging") || path.startsWith("/staging/") || path.startsWith("/api/v1/staging/")) {
            return "staging";
        }
        return "other";
    }
}
