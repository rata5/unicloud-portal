package bg.tusofia.unicloud.portal.ops;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;

/**
 * Turns "the signing key could not be read" into one clear message at start-up (operations spec,
 * "Secrets from the environment"). Without the key pair the portal cannot sign a token, so it
 * refuses to start; Spring's own error would be a long chain about a resource or a conversion.
 * Registered in META-INF/spring.factories.
 */
public class SigningKeyFailureAnalyzer implements FailureAnalyzer {

    private static final String DESCRIPTION = """
            The portal cannot read its signing key pair, so it does not start.
            It needs APP_JWT_PRIVATE_KEY and APP_JWT_PUBLIC_KEY: the RSA keys as PEM text \
            (app.jwt.private-key and app.jwt.public-key). Without them it looks for \
            secrets/private.pem and secrets/public.pem.""";

    private static final String ACTION = """
            In production and on staging, set APP_JWT_PRIVATE_KEY and APP_JWT_PUBLIC_KEY (README, \
            "The pipeline"). Locally, make the pair in secrets/ (README, "Get set up").""";

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (isAboutTheSigningKey(cause)) {
                return new FailureAnalysis(DESCRIPTION, ACTION, failure);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    private static boolean isAboutTheSigningKey(Throwable cause) {
        String message = String.valueOf(cause.getMessage());
        if (message.contains("app.jwt.private-key") || message.contains("app.jwt.public-key")) {
            return true;
        }
        boolean missingFile = cause instanceof FileNotFoundException || cause instanceof NoSuchFileException;
        return missingFile && (message.contains("private.pem") || message.contains("public.pem"))
                || message.contains("java.security.interfaces.RSAPrivateKey")
                || message.contains("java.security.interfaces.RSAPublicKey");
    }
}
