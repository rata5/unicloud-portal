package bg.tusofia.unicloud.portal.accounts;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The cookie session (accounts-and-sign-in spec; react-hypermedia-ui spec, "Return after
 * sign-in"): sign-in, throttling, the BCrypt upgrade, disabled users, the first-password change,
 * sign-out, and single sign-on through the sign-in page. Users and modules come from the staging
 * tools.
 */
@SpringBootTest
@ActiveProfiles("staging")
class SignInTests {

    private static final String SESSION_COOKIE = "__Host-UNICLOUD_SSO";
    private static final String PASSWORD = "a-long-password";
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private Filter sessionRepositoryFilter;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter, springSecurityFilterChain)
                .build();
    }

    @Test
    void wrongCredentialsGetOneMessageAndNoSession() throws Exception {
        String email = newUser();
        Browser browser = new Browser();

        String page = browser.perform(get("/login")).getResponse().getContentAsString();
        assertThat(page).contains("Вход в UniCloud").contains("name=\"csrf\"");

        for (String[] attempt : new String[][]{{email, "not-the-password"}, {"nobody-" + email, PASSWORD}}) {
            MvcResult refused = browser.perform(signIn(browser.csrf(), attempt[0], attempt[1], null));
            assertThat(refused.getResponse().getStatus()).isEqualTo(401);
            assertThat(refused.getResponse().getContentAsString()).contains("Грешен имейл или парола.");
        }
        // Without the form's token nothing happens
        assertThat(browser.perform(post("/login").param("email", email).param("password", PASSWORD))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(browser.perform(get("/password")).getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void signInOpensTheAppOrTheAskedPageOfIt() throws Exception {
        String email = newUser();

        Browser plain = new Browser();
        String before = plain.csrf();
        Cookie anonymous = plain.session;
        MvcResult signedIn = plain.perform(signIn(before, email, PASSWORD, null));
        assertThat(signedIn.getResponse().getRedirectedUrl()).isEqualTo("/app/");
        assertThat(plain.session.getValue()).as("a new session id at sign-in").isNotEqualTo(anonymous.getValue());
        // Already signed in: the sign-in page sends the browser on
        assertThat(plain.perform(get("/login")).getResponse().getRedirectedUrl()).isEqualTo("/app/");

        Browser asked = new Browser();
        assertThat(asked.perform(signIn(asked.csrf(), email, PASSWORD, "/app/courses"))
                .getResponse().getRedirectedUrl()).isEqualTo("/app/courses");

        Browser evil = new Browser();
        assertThat(evil.perform(signIn(evil.csrf(), email, PASSWORD, "//evil.example/app/"))
                .getResponse().getRedirectedUrl()).isEqualTo("/app/");
    }

    @Test
    void tenFailuresLockTheEmailForAWhile() throws Exception {
        String email = newUser();
        Browser browser = new Browser();
        for (int i = 0; i < 10; i++) {
            browser.perform(signIn(browser.csrf(), email, "wrong-" + i, null));
        }

        MvcResult locked = browser.perform(signIn(browser.csrf(), email, PASSWORD, null));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getContentAsString()).contains("Опитайте отново след 15 минути");

        // Another email is not affected
        Browser other = new Browser();
        assertThat(other.perform(signIn(other.csrf(), newUser(), PASSWORD, null))
                .getResponse().getRedirectedUrl()).isEqualTo("/app/");
    }

    @Test
    void aBcryptHashBecomesArgon2AtSignIn() throws Exception {
        String email = "bcrypt-" + UUID.randomUUID().toString().substring(0, 8) + "@test.tu-sofia.bg";
        jdbc.update("""
                INSERT INTO users (email, password_hash, first_name, last_name, role, permissions_mask, enabled)
                VALUES (?, ?, 'Стар', 'Хеш', 'STUDENT', 0, TRUE)""", email, new BCryptPasswordEncoder().encode(PASSWORD));

        Browser browser = new Browser();
        assertThat(browser.perform(signIn(browser.csrf(), email, PASSWORD, null))
                .getResponse().getRedirectedUrl()).isEqualTo("/app/");

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("{argon2}");
        Browser again = new Browser();
        assertThat(again.perform(signIn(again.csrf(), email, PASSWORD, null))
                .getResponse().getRedirectedUrl()).isEqualTo("/app/");
    }

    @Test
    void aUserDisabledDuringTheSessionIsSignedOutAtTheNextRequest() throws Exception {
        String email = newUser();
        Browser browser = signedIn(email);
        assertThat(browser.perform(get("/password")).getResponse().getStatus()).isEqualTo(200);

        jdbc.update("UPDATE users SET enabled = FALSE WHERE email = ?", email);
        assertThat(browser.perform(get("/password")).getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void theFirstPasswordMustBeChangedBeforeAnythingElse() throws Exception {
        String email = newUser();
        jdbc.update("UPDATE users SET must_change_password = TRUE WHERE email = ?", email);

        Browser browser = new Browser();
        assertThat(browser.perform(signIn(browser.csrf(), email, PASSWORD, null))
                .getResponse().getRedirectedUrl()).isEqualTo("/password");
        assertThat(browser.perform(get("/app/")).getResponse().getRedirectedUrl()).isEqualTo("/password");
        MvcResult api = browser.perform(get("/api/v1/portal/me").accept(MediaType.APPLICATION_JSON));
        assertThat(api.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(api.getResponse().getContentAsString()).get("error").asString())
                .isEqualTo("password_change_required");

        String csrf = browser.csrf("/password");
        assertThat(changePassword(browser, csrf, "wrong-current", "new-password-1", "new-password-1"))
                .contains("Текущата парола е грешна.");
        assertThat(changePassword(browser, csrf, PASSWORD, "new-password-1", "new-password-2"))
                .contains("Новите пароли не съвпадат.");
        assertThat(changePassword(browser, csrf, PASSWORD, PASSWORD, PASSWORD))
                .contains("Новата парола трябва да е различна от текущата.");
        assertThat(changePassword(browser, csrf, PASSWORD, "short", "short"))
                .contains("Паролата трябва да е поне 8 знака.");

        MvcResult changed = browser.perform(post("/password").param("csrf", csrf).param("password", PASSWORD)
                .param("new_password", "new-password-1").param("new_password2", "new-password-1"));
        assertThat(changed.getResponse().getRedirectedUrl()).isEqualTo("/app/");
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE email = ?", Boolean.class, email))
                .isFalse();
        Browser fresh = new Browser();
        assertThat(fresh.perform(signIn(fresh.csrf(), email, "new-password-1", null))
                .getResponse().getRedirectedUrl()).isEqualTo("/app/");
    }

    @Test
    void theApiAnswersJsonWithoutASession() throws Exception {
        MvcResult read = mockMvc.perform(get("/api/v1/portal/me").accept(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(read.getResponse().getContentAsString()).get("error").asString())
                .isEqualTo("unauthenticated");

        MvcResult change = mockMvc.perform(post("/api/v1/portal/courses/1/enroll")).andReturn();
        assertThat(change.getResponse().getStatus()).as("a change with no session goes to sign-in").isEqualTo(401);
        assertThat(change.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void signOutEndsTheSessionAndSingleSignOn() throws Exception {
        Browser browser = signedIn(newUser());
        String csrf = browser.csrf("/password");

        assertThat(browser.perform(post("/logout").param("csrf", csrf)).getResponse().getRedirectedUrl())
                .isEqualTo("/login");
        assertThat(browser.perform(get("/password")).getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void aModuleLoginGoesThroughTheSignInPage() throws Exception {
        String email = newUser();
        Module module = newModule();
        Browser browser = new Browser();

        MvcResult toLogin = browser.perform(authorize(module));
        assertThat(toLogin.getResponse().getRedirectedUrl()).endsWith("/login?sso=1");
        assertThat(browser.perform(get("/login").param("sso", "1")).getResponse().getContentAsString())
                .contains("Модул на UniCloud иска да ви идентифицира");

        String resume = browser.perform(signIn(browser.csrf(), email, PASSWORD, null)).getResponse().getRedirectedUrl();
        assertThat(resume).startsWith("/oauth2/authorize?");
        String callback = browser.perform(get(java.net.URI.create(resume)).accept(MediaType.TEXT_HTML)).getResponse().getRedirectedUrl();
        assertThat(callback).startsWith(module.redirectUri() + "?code=");
        assertThat(callback).contains("state=s-1");

        // The module gets the user's tokens; auth_time is when the password was typed
        String code = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");
        JsonNode tokens = json.readTree(mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
                                (module.clientId() + ":" + module.secret()).getBytes(StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", module.redirectUri())
                        .param("code_verifier", module.verifier()))
                .andReturn().getResponse().getContentAsString());
        JsonNode idToken = json.readTree(Base64.getUrlDecoder().decode(tokens.get("id_token").asString().split("\\.")[1]));
        assertThat(idToken.get("sub").asString()).isEqualTo(userId(email));
        assertThat(idToken.has("auth_time")).isTrue();

        // Signed in already: the next module login needs no password
        assertThat(browser.perform(authorize(module)).getResponse().getRedirectedUrl())
                .startsWith(module.redirectUri() + "?code=");
    }

    @Test
    void theFirstPasswordComesBeforeAModuleLogin() throws Exception {
        String email = newUser();
        jdbc.update("UPDATE users SET must_change_password = TRUE WHERE email = ?", email);
        Module module = newModule();
        Browser browser = new Browser();

        browser.perform(authorize(module));
        assertThat(browser.perform(signIn(browser.csrf(), email, PASSWORD, null)).getResponse().getRedirectedUrl())
                .isEqualTo("/password");
        // The module's login waits: authorize goes to the password page too
        assertThat(browser.perform(authorize(module)).getResponse().getRedirectedUrl()).isEqualTo("/password");

        String resume = browser.perform(post("/password").param("csrf", browser.csrf("/password"))
                        .param("password", PASSWORD).param("new_password", "new-password-1")
                        .param("new_password2", "new-password-1"))
                .getResponse().getRedirectedUrl();
        assertThat(resume).startsWith("/oauth2/authorize?");
        assertThat(browser.perform(get(java.net.URI.create(resume)).accept(MediaType.TEXT_HTML)).getResponse().getRedirectedUrl())
                .startsWith(module.redirectUri() + "?code=");
    }

    // ---- helpers ----

    /** One browser: keeps the session cookie between requests, as a real one does. */
    private final class Browser {

        private Cookie session;

        MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
            if (session != null) {
                request.cookie(session);
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            Cookie set = result.getResponse().getCookie(SESSION_COOKIE);
            if (set != null) {
                session = set.getMaxAge() == 0 ? null : set;
            }
            return result;
        }

        String csrf() throws Exception {
            return csrf("/login");
        }

        String csrf(String page) throws Exception {
            String html = perform(get(page)).getResponse().getContentAsString();
            Matcher matcher = CSRF_FIELD.matcher(html);
            assertThat(matcher.find()).as("a csrf field on " + page).isTrue();
            return matcher.group(1);
        }
    }

    private record Module(String clientId, String secret, String redirectUri, String verifier, String challenge) {
    }

    private Browser signedIn(String email) throws Exception {
        Browser browser = new Browser();
        assertThat(browser.perform(signIn(browser.csrf(), email, PASSWORD, null)).getResponse().getRedirectedUrl())
                .isEqualTo("/app/");
        return browser;
    }

    private static MockHttpServletRequestBuilder signIn(String csrf, String email, String password, String next) {
        MockHttpServletRequestBuilder request = post("/login")
                .param("csrf", csrf)
                .param("email", email)
                .param("password", password);
        return next == null ? request : request.param("next", next);
    }

    private String changePassword(Browser browser, String csrf, String current, String next, String repeated)
            throws Exception {
        MvcResult result = browser.perform(post("/password").param("csrf", csrf).param("password", current)
                .param("new_password", next).param("new_password2", repeated));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder authorize(Module module) {
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", module.clientId())
                .queryParam("redirect_uri", module.redirectUri())
                .queryParam("scope", "openid profile unicloud")
                .queryParam("state", "s-1")
                .queryParam("code_challenge", module.challenge())
                .queryParam("code_challenge_method", "S256")
                .accept(MediaType.TEXT_HTML);
    }

    private String newUser() throws Exception {
        String email = "signin-" + UUID.randomUUID().toString().substring(0, 8) + "@test.tu-sofia.bg";
        mockMvc.perform(post("/api/v1/staging/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "firstName": "Тест", "lastName": "Вход", "role": "STUDENT",
                         "student": {"facultyNumber": "%s", "specialty": "Компютърни науки", "studyYear": 2}}"""
                        .formatted(email, PASSWORD, "SI" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))));
        return email;
    }

    private String userId(String email) {
        return String.valueOf(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email));
    }

    private Module newModule() throws Exception {
        String clientId = "signin-" + UUID.randomUUID().toString().substring(0, 8);
        String redirectUri = "https://" + clientId + ".example/auth/callback";
        JsonNode answer = json.readTree(mockMvc.perform(post("/api/v1/staging/sso-clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId": "%s", "name": "Sign-in test", "redirectUris": ["%s"]}"""
                                .formatted(clientId, redirectUri)))
                .andReturn().getResponse().getContentAsString());
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return new Module(clientId, answer.get("clientSecret").asString(), redirectUri, verifier, challenge);
    }
}
