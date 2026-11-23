package bg.tusofia.unicloud.portal.sso.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * The key pair single sign-on signs ID and access tokens with ({@code APP_JWT_PRIVATE_KEY},
 * {@code APP_JWT_PUBLIC_KEY}; locally secrets/*.pem). Published at /oauth2/jwks for the modules.
 * The key id ("kid") is the key's RFC 7638 thumbprint, so a future second key gets a different one.
 * Without the pair the portal does not start (SigningKeyFailureAnalyzer says which variables to set).
 */
@Configuration
public class SigningKeyConfig {

    @Bean
    JWKSource<SecurityContext> jwkSource(
            @Value("${app.jwt.public-key}") RSAPublicKey publicKey,
            @Value("${app.jwt.private-key}") RSAPrivateKey privateKey) throws JOSEException {

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyIDFromThumbprint()
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }
}
