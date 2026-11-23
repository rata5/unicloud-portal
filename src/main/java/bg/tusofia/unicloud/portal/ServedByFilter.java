package bg.tusofia.unicloud.portal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds X-Served-By: <replica name> to every response, so load balancing is visible. Runs before
 * Spring Security, so refused requests (401, 403) carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServedByFilter extends OncePerRequestFilter {

    private final String replica = Replica.name();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Served-By", replica);
        chain.doFilter(request, response);
    }
}
