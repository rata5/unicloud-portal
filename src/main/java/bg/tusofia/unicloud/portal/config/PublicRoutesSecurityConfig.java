package bg.tusofia.unicloud.portal.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Every request the other chains leave: directory (0, DirectorySecurityConfig), single sign-on
 * (1, SsoAuthorizationServerConfig) and the cookie session of the portal UI (2,
 * PortalSessionSecurityConfig). Only public routes are left here, and they need no login:
 * <ul>
 *   <li>{@code /health} and {@code /} (README, "Rules the code must follow");</li>
 *   <li>the API reference, {@code /api-docs} and {@code /openapi.json};</li>
 *   <li>the staging tools, only with the staging profile; without it they answer 404.</li>
 * </ul>
 * Everything else answers 404, for every caller and every method: the portal's old JWT login, its
 * static pages and their REST routes are gone (complete-java-portal task 2.6), and a route added
 * later stays closed until a chain names it. This chain reads no session and no token.
 */
@Configuration
public class PublicRoutesSecurityConfig {

    @Bean
    @Order(3)
    SecurityFilterChain publicRoutesSecurityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        return http
                // Nothing here signs anyone in, so a forged request gains nothing. The staging
                // tools' JSON API is open by design (staging-tools spec).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .headers(SecurityHeaders.everywhere())
                .authorizeHttpRequests(auth -> auth
                        // Error pages for requests already answered with an error status, for
                        // example the plain 400 of /oauth2/authorize for an unknown module
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // The deploy polls /health until it reports the new commit, and / shows
                        // which replica answered
                        .requestMatchers(HttpMethod.GET, "/health", "/").permitAll()
                        // The API reference (springdoc): it describes the module-facing API and holds no data
                        .requestMatchers(HttpMethod.GET,
                                "/openapi.json", "/openapi.json/**", "/api-docs", "/api-docs/**", "/swagger-ui/**").permitAll()
                        // Staging only (stagingAbsent below answers 404 without the profile)
                        .requestMatchers("/staging", "/staging/**", "/api/v1/staging/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_NOT_FOUND))
                        .accessDeniedHandler((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_NOT_FOUND)))
                .addFilterBefore(stagingAbsent(environment), SecurityContextHolderFilter.class)
                .build();
    }

    /** Without the staging profile the staging tools' paths answer 404 for every method. */
    private static Filter stagingAbsent(Environment environment) {
        boolean staging = environment.matchesProfiles("staging");
        return (request, response, chain) -> {
            String path = ((HttpServletRequest) request).getRequestURI();
            if (!staging && (path.equals("/staging") || path.startsWith("/staging/") || path.startsWith("/api/v1/staging/"))) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(request, response);
        };
    }
}
