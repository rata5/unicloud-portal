package bg.tusofia.unicloud.portal.accounts.web;

import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.accounts.application.SignInThrottle;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;
import java.util.Optional;

/**
 * Sign-in with email and password (accounts-and-sign-in spec), a server-rendered page so a
 * module's login never waits for the app. Ported from the fork's {@code accounts/handlers.rs}:
 * <ul>
 *   <li>a wrong email, a wrong password and a disabled user get the same message, and an
 *       unknown email costs the same password check as a real one;</li>
 *   <li>10 failures in 15 minutes lock the email for 15 minutes (SignInThrottle);</li>
 *   <li>a BCrypt hash from before is replaced by an Argon2id one at a successful sign-in;</li>
 *   <li>afterwards: the first-password change if due, else the waiting SSO login, else a valid
 *       {@code next}, else the app (PortalSession).</li>
 * </ul>
 */
@Controller
public class SignInController {

    static final String BAD_LOGIN = "Грешен имейл или парола.";
    static final String LOCKED = "Твърде много неуспешни опити. Опитайте отново след 15 минути.";

    private static final Logger log = LoggerFactory.getLogger(SignInController.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SignInThrottle throttle;
    private final PortalSession portalSession;
    private volatile String dummyHash;

    public SignInController(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            SignInThrottle throttle,
            PortalSession portalSession) {

        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.portalSession = portalSession;
    }

    @GetMapping("/login")
    public String page(
            @RequestParam(required = false) String sso,
            @RequestParam(required = false) String next,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        PortalUser current = PortalUser.current(request);
        if (current != null) {
            return "redirect:" + portalSession.afterSignIn(request, response, next, current.mustChangePassword());
        }
        return view(model, request, sso != null, next, "", null);
    }

    @PostMapping("/login")
    public String signIn(
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String password,
            @RequestParam(required = false) String next,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        String address = email.strip().toLowerCase(Locale.ROOT);
        if (throttle.isLocked(address)) {
            response.setStatus(429);
            return view(model, request, false, next, address, LOCKED);
        }

        Optional<User> found = address.isEmpty() ? Optional.empty() : users.findByEmail(address);
        boolean accepted = found
                .map(user -> passwordEncoder.matches(password, user.getPasswordHash()) && user.isEnabled())
                .orElseGet(() -> {
                    // The same work as a real check, so the answer time does not reveal which emails exist
                    passwordEncoder.matches(password, dummyHash());
                    return false;
                });
        if (!accepted) {
            throttle.recordFailure(address);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return view(model, request, false, next, address, BAD_LOGIN);
        }

        throttle.clear(address);
        User user = found.get();
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.rehashPassword(passwordEncoder.encode(password));
            user = users.save(user);
        }
        portalSession.signIn(request, response, user);
        log.info("User {} signed in", user.getId());
        return "redirect:" + portalSession.afterSignIn(request, response, next, user.mustChangePassword());
    }

    private String view(Model model, HttpServletRequest request, boolean sso, String next, String email, String error) {
        model.addAttribute("csrf", csrfToken(request));
        model.addAttribute("sso", sso);
        model.addAttribute("next", PortalSession.appPath(next));
        model.addAttribute("email", email);
        model.addAttribute("error", error);
        return "login";
    }

    public static String csrfToken(HttpServletRequest request) {
        return request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token ? token.getToken() : "";
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode("unicloud-dummy-password");
            dummyHash = hash;
        }
        return hash;
    }
}
