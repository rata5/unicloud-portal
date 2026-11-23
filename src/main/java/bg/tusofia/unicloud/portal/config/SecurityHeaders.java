package bg.tusofia.unicloud.portal.config;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Browser hardening for every response (operations spec, "Security headers"). Each filter chain
 * applies it: SSO, directory and the portal.
 *
 * <ul>
 *   <li>Scripts only from the portal's own files: no inline script, no other site.</li>
 *   <li>Styles also inline, because pages set a width or a colour in a {@code style}
 *       attribute; images also as {@code data:} URLs (Swagger UI's icons).</li>
 *   <li>No page may be framed by another site ({@code frame-ancestors 'none'} and
 *       {@code X-Frame-Options: DENY}), so a login form cannot be overlaid.</li>
 *   <li>{@code nosniff}, and only the origin leaves the site in the {@code Referer}.</li>
 * </ul>
 */
public final class SecurityHeaders {

    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "object-src 'none'");

    private SecurityHeaders() {
    }

    public static Customizer<HeadersConfigurer<HttpSecurity>> everywhere() {
        return headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
    }
}
