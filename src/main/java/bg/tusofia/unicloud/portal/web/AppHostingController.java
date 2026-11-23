package bg.tusofia.unicloud.portal.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;

/**
 * The React app at /app and the shared UI package at /ui/v1 (react-hypermedia-ui spec, "The
 * portal app" and "Shared UI package"). Ported from the fork's {@code spa.rs}. The Docker build
 * puts the built files in {@code static/app/} and {@code static/ui/v1/} (Dockerfile, web stage).
 *
 * <ul>
 *   <li>A built file under /app is served as it is: hashed assets cached for a year, anything
 *       else not cached.</li>
 *   <li>Any other path under /app gets the app's index.html, so the app's router decides
 *       (/app/courses is a page of the app). A missing file under /app/assets is 404: a stale
 *       page asking for an old asset must not get HTML back.</li>
 *   <li>/ui/v1 is readable from any origin (CORS in UiPackageConfig), cached for an hour; a
 *       published version never changes.</li>
 * </ul>
 */
@Controller
public class AppHostingController {

    private static final String APP = "static/app/";
    private static final String UI = "static/ui/v1/";

    @GetMapping({"/app", "/app/", "/app/**"})
    public ResponseEntity<Resource> app(HttpServletRequest request) {
        String path = relative(request.getRequestURI(), "/app");
        Resource file = file(APP + path);
        if (file != null) {
            CacheControl cache = path.startsWith("assets/")
                    ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                    : CacheControl.noCache();
            return serve(file, cache);
        }
        if (path.startsWith("assets/")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
        }
        Resource index = file(APP + "index.html");
        if (index == null) {
            // A build without the web stage (a local ./mvnw spring-boot:run): run npm run dev in web/
            return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_HTML).body(index);
    }

    @GetMapping("/ui/v1/**")
    public ResponseEntity<Resource> uiPackage(HttpServletRequest request) {
        Resource file = file(UI + relative(request.getRequestURI(), "/ui/v1"));
        if (file == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("Cross-Origin-Resource-Policy", "cross-origin")
                .contentType(MediaTypeFactory.getMediaType(file).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(file);
    }

    /** The path below {@code prefix}, without a leading slash; "" for the prefix itself. */
    private static String relative(String uri, String prefix) {
        String rest = uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
        return rest.startsWith("/") ? rest.substring(1) : rest;
    }

    /** A file of the build, or null; never a directory and never a path that climbs out. */
    private static Resource file(String location) {
        if (location.contains("..") || location.contains("\\") || location.endsWith("/")) {
            return null;
        }
        ClassPathResource resource = new ClassPathResource(location);
        try {
            return resource.exists() && resource.isReadable() && resource.contentLength() > 0 ? resource : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static ResponseEntity<Resource> serve(Resource file, CacheControl cache) {
        return ResponseEntity.ok()
                .cacheControl(cache)
                .contentType(MediaTypeFactory.getMediaType(file).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(file);
    }
}
