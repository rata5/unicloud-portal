package bg.tusofia.unicloud.portal.accounts.config;

import bg.tusofia.unicloud.portal.accounts.web.PortalApiErrors;
import bg.tusofia.unicloud.portal.accounts.web.PortalUserFilter;
import bg.tusofia.unicloud.portal.config.SecurityHeaders;
import bg.tusofia.unicloud.portal.sso.infrastructure.SsoSessionMaxAgeFilter;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import java.time.Clock;
import java.time.Duration;

/**
 * The cookie session for the portal UI (ADR 0003; accounts-and-sign-in spec): the sign-in and
 * password pages, sign-out, the app at /app, its JSON API under /api/v1/portal, and the shared UI
 * package at /ui/v1. The session is the same one the SSO chain reads, cookie __Host-UNICLOUD_SSO.
 *
 * <ul>
 *   <li>CSRF on every change: the {@code X-CSRF-Token} header (the app) or the {@code csrf} form
 *       field (server pages, the app's logout form).</li>
 *   <li>No request is saved for after sign-in: only the SSO chain saves one (the waiting
 *       /oauth2/authorize), and this chain must not overwrite it.</li>
 *   <li>API errors are JSON, never redirects (PortalApiErrors).</li>
 * </ul>
 * Order 2: after the directory (0) and SSO (1) chains, before the old portal chain (3).
 */
@Configuration
public class PortalSessionSecurityConfig {

    /** The CSRF tokens of the session, shared with PortalSession (a new one at sign-in). */
    @Bean
    CsrfTokenRepository portalCsrfTokens() {
        HttpSessionCsrfTokenRepository tokens = new HttpSessionCsrfTokenRepository();
        tokens.setHeaderName("X-CSRF-Token");
        tokens.setParameterName("csrf");
        return tokens;
    }

    @Bean
    @Order(2)
    SecurityFilterChain portalSessionSecurityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository portalCsrfTokens,
            UserRepository users,
            @Value("${app.sso.session-max-age}") Duration sessionMaxAge) throws Exception {

        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return http
                .securityMatcher("/login", "/password", "/logout", "/api/v1/portal/**", "/app", "/app/**", "/ui/v1/**")
                // The plain token handler: the value from GET /api/v1/portal/me works for the
                // whole session, as the app expects
                .csrf(csrf -> csrf
                        .csrfTokenRepository(portalCsrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .headers(SecurityHeaders.everywhere())
                // Ends the session for the whole browser, which also ends single sign-on
                .logout(logout -> logout
                        .logoutRequestMatcher(path.matcher(org.springframework.http.HttpMethod.POST, "/logout"))
                        .logoutSuccessUrl("/login")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/logout", "/app", "/app/**", "/ui/v1/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(PortalApiErrors.unauthenticated(),
                                path.matcher("/api/v1/portal/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE)
                        .accessDeniedHandler(PortalApiErrors.accessDenied()))
                .addFilterAfter(new SsoSessionMaxAgeFilter(sessionMaxAge, Clock.systemUTC()),
                        SecurityContextHolderFilter.class)
                .addFilterAfter(new PortalUserFilter(users), SsoSessionMaxAgeFilter.class)
                .build();
    }
}
