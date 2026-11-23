package bg.tusofia.unicloud.portal.sso.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import java.util.Collection;
import java.util.List;

/**
 * Decoders for the access tokens the portal issues to modules (docs/sso.md). Both check the RS256
 * signature against the portal's own key, the time claims and the {@code typ: at+jwt} header
 * (RFC 9068). Each accepts one kind of token only:
 * <ul>
 *   <li>{@link #forUserInfo}: tokens from a user's login (audience: the module), at /userinfo;</li>
 *   <li>{@link #forDirectory}: client-credentials tokens (audience {@value #DIRECTORY_AUDIENCE},
 *       scope {@value #DIRECTORY_SCOPE}), at /api/v1/directory.</li>
 * </ul>
 * A token of the other kind is refused with 401 {@code invalid_token}.
 */
public final class AccessTokenDecoders {

    /** The {@code aud} of client-credentials tokens: the directory API. */
    public static final String DIRECTORY_AUDIENCE = "unicloud-directory";

    /** The scope a module needs for the directory API, granted at registration. */
    public static final String DIRECTORY_SCOPE = "directory";

    private AccessTokenDecoders() {
    }

    /**
     * Tokens without a {@code typ} (issued before the portal set one, valid for at most 30
     * minutes) still decode. An ID token decodes too, but the library then finds no access token
     * with that value in its store and answers 401.
     */
    public static JwtDecoder forUserInfo(JWKSource<SecurityContext> jwkSource) {
        JwtTypeValidator type = new JwtTypeValidator("at+jwt");
        type.setAllowEmpty(true);
        return decoder(jwkSource, true, List.of(type, rejectAudience(DIRECTORY_AUDIENCE)));
    }

    /**
     * Only client-credentials tokens for the directory, and only while the portal still holds
     * them as valid (not expired, not invalidated).
     */
    public static JwtDecoder forDirectory(
            JWKSource<SecurityContext> jwkSource,
            OAuth2AuthorizationService authorizationService) {

        return decoder(jwkSource, false, List.of(
                new JwtTypeValidator("at+jwt"),
                requireAudience(DIRECTORY_AUDIENCE),
                requireScope(DIRECTORY_SCOPE),
                stillActive(authorizationService)));
    }

    private static JwtDecoder decoder(
            JWKSource<SecurityContext> jwkSource,
            boolean allowNoType,
            List<OAuth2TokenValidator<Jwt>> validators) {

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(allowNoType
                ? new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt"), null)
                : new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        // NimbusJwtDecoder checks the claims itself, with the validators below
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        // Spring's default validators accept only typ JWT; keep their time checks
        List<OAuth2TokenValidator<Jwt>> all = new java.util.ArrayList<>();
        all.add(new JwtTimestampValidator());
        all.addAll(validators);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(all));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requireAudience(String audience) {
        return jwt -> contains(jwt.getAudience(), audience)
                ? OAuth2TokenValidatorResult.success()
                : invalid("This token is not for the directory API");
    }

    private static OAuth2TokenValidator<Jwt> rejectAudience(String audience) {
        return jwt -> contains(jwt.getAudience(), audience)
                ? invalid("A directory token is not a user's token")
                : OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidator<Jwt> requireScope(String scope) {
        return jwt -> {
            Object scopes = jwt.getClaims().get("scope");
            boolean granted = scopes instanceof Collection<?> list
                    ? list.contains(scope)
                    : scopes instanceof String text && List.of(text.split(" ")).contains(scope);
            return granted ? OAuth2TokenValidatorResult.success() : invalid("The token lacks scope " + scope);
        };
    }

    private static OAuth2TokenValidator<Jwt> stillActive(OAuth2AuthorizationService authorizationService) {
        return jwt -> {
            OAuth2Authorization authorization =
                    authorizationService.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
            boolean active = authorization != null
                    && authorization.getAccessToken() != null
                    && authorization.getAccessToken().isActive();
            return active ? OAuth2TokenValidatorResult.success() : invalid("The token was revoked or has expired");
        };
    }

    private static boolean contains(List<String> audience, String expected) {
        return audience != null && audience.contains(expected);
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
