package bg.tusofia.unicloud.portal.accounts.web;

import bg.tusofia.unicloud.portal.sso.infrastructure.ModuleLogins;
import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The password change, and the forced first change (accounts-and-sign-in spec, "First password
 * must be changed"). Ported from the fork's {@code accounts/handlers.rs}, with its messages.
 * Only for a signed-in user (PortalSessionSecurityConfig); PortalUserFilter sends a user who must
 * change the password here from every other page.
 */
@Controller
public class PasswordController {

    static final String WRONG_CURRENT = "Текущата парола е грешна.";
    static final String MISMATCH = "Новите пароли не съвпадат.";
    static final String SAME = "Новата парола трябва да е различна от текущата.";
    static final String TOO_SHORT = "Паролата трябва да е поне 8 знака.";
    static final String TOO_LONG = "Паролата трябва да е най-много 72 знака.";

    private static final Logger log = LoggerFactory.getLogger(PasswordController.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PortalSession portalSession;
    private final ModuleLogins moduleLogins;

    public PasswordController(UserRepository users, PasswordEncoder passwordEncoder, PortalSession portalSession,
                              ModuleLogins moduleLogins) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.portalSession = portalSession;
        this.moduleLogins = moduleLogins;
    }

    /** The logout confirmation: a module asked to sign the user out without proving it (sso-provider spec). */
    @GetMapping("/logout")
    public String confirmLogout(HttpServletRequest request, Model model) {
        if (PortalUser.current(request) == null) {
            return "redirect:/login";
        }
        // As on the fork, the confirmation shows no user in the top bar
        model.addAttribute("csrf", SignInController.csrfToken(request));
        return "logout";
    }

    @GetMapping("/password")
    public String page(HttpServletRequest request, Model model) {
        return view(model, request, null);
    }

    @PostMapping("/password")
    public String change(
            @RequestParam(defaultValue = "") String password,
            @RequestParam(name = "new_password", defaultValue = "") String newPassword,
            @RequestParam(name = "new_password2", defaultValue = "") String repeated,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        PortalUser current = PortalUser.current(request);
        User user = users.findById(current.id()).orElseThrow();
        String problem = null;
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            problem = WRONG_CURRENT;
        } else if (!newPassword.equals(repeated)) {
            problem = MISMATCH;
        } else if (newPassword.equals(password)) {
            problem = SAME;
        } else if (newPassword.codePointCount(0, newPassword.length()) < 8) {
            problem = TOO_SHORT;
        } else if (newPassword.codePointCount(0, newPassword.length()) > 72) {
            problem = TOO_LONG;
        }
        if (problem != null) {
            return view(model, request, problem);
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        users.save(user);
        // Tokens modules got before the change stop working at /userinfo
        moduleLogins.revokeAllOf(user.getId());
        log.info("User {} changed the password", user.getId());
        return "redirect:" + portalSession.afterSignIn(request, response, null, false);
    }

    private String view(Model model, HttpServletRequest request, String error) {
        PortalUser current = PortalUser.current(request);
        model.addAttribute("csrf", SignInController.csrfToken(request));
        model.addAttribute("forced", current != null && current.mustChangePassword());
        // The top bar (layout.html) shows the user, with the role and the sign-out button
        model.addAttribute("name", current == null ? "" : current.name());
        model.addAttribute("roleLabel", current == null ? null : PortalUser.roleLabel(current.role()));
        model.addAttribute("error", error);
        return "password";
    }
}
