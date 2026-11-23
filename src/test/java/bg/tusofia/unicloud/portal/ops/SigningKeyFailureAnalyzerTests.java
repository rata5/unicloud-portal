package bg.tusofia.unicloud.portal.ops;

import bg.tusofia.unicloud.portal.PortalApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Without its signing key the portal refuses to start, and says which variables to set. */
class SigningKeyFailureAnalyzerTests {

    @Test
    void aMissingKeyStopsTheStartAndNamesTheVariables() {
        // Command-line arguments: they win over application.properties (builder properties do not)
        Throwable failure = catchThrowable(() -> {
            ConfigurableApplicationContext started = new SpringApplicationBuilder(PortalApplication.class)
                    .run("--server.port=0",
                            "--app.jwt.private-key=file:./no-such-dir/private.pem",
                            "--app.jwt.public-key=file:./no-such-dir/public.pem");
            started.close();
        });

        assertThat(failure).as("the portal must not start without its keys").isNotNull();
        FailureAnalysis analysis = new SigningKeyFailureAnalyzer().analyze(failure);
        assertThat(analysis).as("not recognised: %s", failure).isNotNull();
        assertThat(analysis.getDescription()).contains("APP_JWT_PRIVATE_KEY", "APP_JWT_PUBLIC_KEY");
    }

    @Test
    void otherFailuresAreLeftToSpring() {
        assertThat(new SigningKeyFailureAnalyzer().analyze(new IllegalStateException("database is down"))).isNull();
    }
}
