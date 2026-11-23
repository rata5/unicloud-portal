package bg.tusofia.unicloud.portal.directory.config;

import bg.tusofia.unicloud.portal.config.SecurityHeaders;
import bg.tusofia.unicloud.portal.sso.infrastructure.AccessTokenDecoders;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The directory API's own filter chain (module-directory spec). Every route under
 * /api/v1/directory needs a module's client-credentials token with scope {@code directory}
 * (AccessTokenDecoders.forDirectory); anything else is 401. Stateless: it never reads the
 * portal's session. It runs before the portal chain in SecurityConfig and matches only its paths.
 */
@Configuration
public class DirectorySecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain directorySecurityFilterChain(
            HttpSecurity http,
            JWKSource<SecurityContext> jwkSource,
            OAuth2AuthorizationService authorizationService) throws Exception {

        return http
                .securityMatcher("/api/v1/directory/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .headers(SecurityHeaders.everywhere())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(AccessTokenDecoders.forDirectory(jwkSource, authorizationService))))
                .build();
    }
}
