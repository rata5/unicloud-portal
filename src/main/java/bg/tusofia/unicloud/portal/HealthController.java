package bg.tusofia.unicloud.portal;

import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints the pipeline and the demo rely on. Keep both when you add the real API.
 */
@RestController
public class HealthController {

    private final String version;
    private final String replica = Replica.name();

    public HealthController(@Value("${GIT_SHA:dev}") String version) {
        this.version = version;
    }

    /** Cheap on purpose: no database call. The deploy waits until this shows the new commit SHA. */
    @Operation(summary = "Liveness and the running commit", tags = "Operations")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "version", version);
    }

    /**
     * Shows which replica answered, for the load-balancing demo (curl and other JSON clients). A
     * browser, which asks for text/html, goes to the app instead; the app sends it to sign-in if
     * there is no session (operations spec, "Replica identity").
     */
    @GetMapping("/")
    public ResponseEntity<Map<String, String>> info(
            @RequestHeader(value = HttpHeaders.ACCEPT, defaultValue = "") String accept) {
        if (accept.contains(MediaType.TEXT_HTML_VALUE)) {
            return ResponseEntity.status(303).location(URI.create("/app/")).build();
        }
        return ResponseEntity.ok(Map.of("service", "unicloud-portal", "replica", replica, "version", version));
    }
}
