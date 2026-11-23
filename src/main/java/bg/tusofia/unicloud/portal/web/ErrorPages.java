package bg.tusofia.unicloud.portal.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;

/**
 * The error page for browsers (templates/error.html), as on the fork's Rust portal
 * (src/ui/components.rs, error_page; messages from src/error.rs and src/oidc). Only for answers
 * that are HTML: API callers still get JSON. The page shows the status and a message chosen here,
 * never the exception's text.
 */
@Component
public class ErrorPages implements ErrorViewResolver {

    /** A request attribute with a message for the page, set before {@code sendError}. */
    public static final String MESSAGE = ErrorPages.class.getName() + ".MESSAGE";

    static final String BAD_REQUEST = "Заявката е невалидна.";
    static final String FORBIDDEN = "Нямате права за това действие.";
    static final String NOT_FOUND = "Страницата не е намерена.";
    static final String FAILED = "Възникна грешка. Опитайте отново.";
    static final String BAD_MODULE_LOGIN =
            "Непознат модул (client_id) или неразрешен адрес за връщане (redirect_uri). Проверете регистрацията му.";
    static final String BAD_MODULE_LOGOUT =
            "Входът е твърде стар или непознат, или адресът след изход не е разрешен за този модул.";

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Map<String, Object> page = new HashMap<>(model);
        page.put("status", status.value());
        page.put("pageMessage", message(request, status));
        return new ModelAndView("error", page, status);
    }

    static String message(HttpServletRequest request, HttpStatus status) {
        if (request.getAttribute(MESSAGE) instanceof String message) {
            return message;
        }
        String path = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        return switch (status) {
            case BAD_REQUEST -> path.startsWith("/oauth2/authorize") ? BAD_MODULE_LOGIN
                    : path.startsWith("/connect/logout") ? BAD_MODULE_LOGOUT
                    : BAD_REQUEST;
            case FORBIDDEN -> FORBIDDEN;
            case NOT_FOUND -> NOT_FOUND;
            default -> FAILED;
        };
    }
}
