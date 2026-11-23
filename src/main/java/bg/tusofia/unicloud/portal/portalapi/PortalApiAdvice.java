package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.accounts.application.AccountCreation;
import bg.tusofia.unicloud.portal.exception.DuplicateResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every error of the app's JSON API as {@code {error, message}}, never cached. Only for the
 * controllers in this package; the rest of the portal keeps GlobalExceptionHandler.
 */
@RestControllerAdvice(basePackages = "bg.tusofia.unicloud.portal.portalapi")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortalApiAdvice {

    private static final Logger log = LoggerFactory.getLogger(PortalApiAdvice.class);

    @ExceptionHandler(PortalApiException.class)
    ResponseEntity<Map<String, String>> portal(PortalApiException e) {
        return error(e.status(), e.error(), e.getMessage());
    }

    @ExceptionHandler(AccountCreation.Refused.class)
    ResponseEntity<Map<String, String>> refused(AccountCreation.Refused e) {
        return e.duplicate() ? error(409, "conflict", e.getMessage()) : error(400, "invalid", e.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateResourceException e) {
        return error(409, "conflict", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return error(400, "invalid", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> fields(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(400, "invalid", message.isEmpty() ? "Невалидни данни." : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return error(400, "invalid", "Невалидни данни. Попълнете всички полета.");
    }

    /** A path value of the wrong type, for example a letter where an id goes: nothing is there. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(404, "not_found", "Не е намерено.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return error(403, "forbidden", "Нямате права за това действие.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Unexpected error in the portal API", e);
        return error(500, "server_error", "Възникна грешка. Опитайте отново.");
    }

    static ResponseEntity<Map<String, String>> error(int status, String error, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
