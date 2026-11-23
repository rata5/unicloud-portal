package bg.tusofia.unicloud.portal.auth.infrastructure.security.config;

import bg.tusofia.unicloud.portal.accounts.infrastructure.PortalPasswordEncoder;
import bg.tusofia.unicloud.portal.config.SecurityHeaders;
import jakarta.servlet.DispatcherType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.jwt.public-key}")
    private RSAPublicKey publicKey;

    @Value("${app.jwt.private-key}")
    private RSAPrivateKey privateKey;

    @Value("${app.jwt.issuer}")
    private String issuer;

    @Value("${app.jwt.audience}")
    private String audience;


    /**
     * The portal's old API and static pages, with the portal's own JWT. It runs last and takes
     * every request the other chains leave: directory (0, DirectorySecurityConfig), SSO (1,
     * SsoAuthorizationServerConfig) and the cookie session for /login, /password, /logout, the
     * app and its JSON API (2, PortalSessionSecurityConfig). It goes once the app replaces the
     * old pages (complete-java-portal task 2.6).
     */
    @Bean
    @Order(3)
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment)
            throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .headers(SecurityHeaders.everywhere())
                .authorizeHttpRequests(auth -> auth
                        // Error pages for requests already answered with an error status, for
                        // example the plain 400 of /oauth2/authorize for an unknown module
                        // (sso-provider spec). Without this an anonymous caller got 401 instead.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // The deploy polls /health until it reports the new commit, and / shows
                        // which replica answered (README, "Rules the code must follow")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/health",
                                "/"
                        ).permitAll()
                        // The API reference (springdoc): it describes the module-facing API and holds
                        // no data. Calling an endpoint from it still needs that endpoint's token.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/openapi.json",
                                "/openapi.json/**",
                                "/api-docs",
                                "/api-docs/**",
                                "/swagger-ui/**"
                        ).permitAll()
                        // The frontend's static files (src/main/resources/static). The pages hold no
                        // data: they call the API, which still requires a token.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/*.html",
                                "/css/**",
                                "/js/**",
                                "/favicon.ico"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/login"
                        ).permitAll()
                        // Ends the SSO browser session, if any. Open so that logout works with an
                        // expired token too. Another site cannot call it: a cross-site DELETE needs
                        // CORS consent, and the SameSite=Lax cookie is not sent with it anyway.
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/v1/auth/sso-session"
                        ).permitAll()
                        // Staging only: the staging tools need no login (StagingToolsController).
                        // On any other profile the routes do not exist and stay closed.
                        .requestMatchers("/staging", "/staging/**", "/api/v1/staging/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                environment.matchesProfiles("staging")))
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                jwtAuthenticationConverter()
                        ))
                )
                .exceptionHandling(ex -> ex
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\n" +
                                               "    \"error\": \"You do not have permission to perform this action\"\n" +
                                               "}\n");
                }))
                .build();
    }

    /** Argon2id for new hashes; BCrypt hashes from before still verify (PortalPasswordEncoder). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new PortalPasswordEncoder();
    }

    /**
     * The signing key pair. Also published at /oauth2/jwks for the SSO modules. The key
     * id ("kid") is the key's RFC 7638 thumbprint, so a future second key gets a different one.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource() throws JOSEException {
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyIDFromThumbprint()
                .build();

        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(publicKey).build();

        JwtClaimValidator<List<String>> audienceValidator =
                new JwtClaimValidator<>(
                        JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience)
                );

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        audienceValidator
                )
        );

        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        // JwtAccessTokenIssuer puts the user's single role in the "role" claim; it becomes the
        // ROLE_<name> authority that hasRole('<name>') checks.
        JwtGrantedAuthoritiesConverter roles =
                new JwtGrantedAuthoritiesConverter();

        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");

        JwtGrantedAuthoritiesConverter permissions =
                new JwtGrantedAuthoritiesConverter();

        permissions.setAuthoritiesClaimName("permissions");
        permissions.setAuthorityPrefix("");

        DelegatingJwtGrantedAuthoritiesConverter authorities =
                new DelegatingJwtGrantedAuthoritiesConverter(
                        roles, permissions);

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return converter;
    }
}