package bg.tusofia.unicloud.portal.sso;

import bg.tusofia.unicloud.portal.support.PortalBrowser;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import bg.tusofia.unicloud.portal.support.TestUsers;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.RequestContextFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staging's rules (app.sso.callback-policy=any): a module may use any http or https callback,
 * registered or not, for login and for logout.
 */
@SpringBootTest(properties = "app.sso.callback-policy=any")
class SsoAnyCallbackTests {


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
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestUsers users;
    private PortalBrowser admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter, new RequestContextFilter(), springSecurityFilterChain)
                .build();
        users = new TestUsers(userRepository, passwordEncoder, jdbc);
    }

    @Test
    void anyHostCanBeRegistered() throws Exception {
        register("module-" + shortId(), "http://dev-box.lab:3000/auth/callback");
    }

    @Test
    void anUnregisteredCallbackWorksForLoginAndLogout() throws Exception {
        String clientId = "module-" + shortId();
        String secret = register(clientId, "https://registered.example/auth/callback");
        String callback = "http://my-laptop.local:5173/auth/callback";
        String logoutTarget = "http://my-laptop.local:5173/bye";
        Pkce pkce = new Pkce();

        String authorize = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", callback)
                .queryParam("scope", "openid")
                .queryParam("state", "s")
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();

        PortalBrowser browser = new PortalBrowser(mockMvc);
        assertThat(browser.perform(get(URI.create(authorize)).accept(MediaType.TEXT_HTML))
                .getResponse().getRedirectedUrl()).endsWith("/login?sso=1");
        String email = users.student("Тест", "Колбек", TestUsers.facultyNumber(), 1).email();
        String continueUrl = browser.signIn(email, TestUsers.PASSWORD).getResponse().getRedirectedUrl();

        String redirect = browser.perform(get(URI.create(continueUrl)).accept(MediaType.TEXT_HTML))
                .getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith(callback + "?code=");
        String code = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");

        JsonNode tokens = json.readTree(mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
                                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", callback)
                        .param("code_verifier", pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        String afterLogout = browser.perform(get("/connect/logout")
                        .queryParam("id_token_hint", tokens.get("id_token").asString())
                        .queryParam("client_id", clientId)
                        .queryParam("post_logout_redirect_uri", logoutTarget))
                .getResponse().getRedirectedUrl();
        assertThat(afterLogout).isEqualTo(logoutTarget);
    }

    @Test
    void stillNoNonWebCallbacks() throws Exception {
        assertThat(admin().post(MODULES, clientJson("module-" + shortId(), "javascript:alert(1)")).status())
                .isEqualTo(400);
    }

    /** The app's route for registering a module (an admin's page, Модули). */
    private static final String MODULES = "/api/v1/portal/admin/modules";

    private PortalBrowser admin() throws Exception {
        if (admin == null) {
            admin = PortalBrowser.signedIn(mockMvc, users.staff("ADMIN", null).email(), TestUsers.PASSWORD);
        }
        return admin;
    }

    private String register(String clientId, String redirectUri) throws Exception {
        PortalBrowser.Answer registered = admin().post(MODULES, clientJson(clientId, redirectUri));
        assertThat(registered.status()).as("registration of " + clientId).isEqualTo(201);
        return registered.json().get("clientSecret").asString();
    }

    private static String clientJson(String clientId, String redirectUri) {
        return """
                {"clientId": "%s", "name": "Test module", "redirectUris": ["%s"]}
                """.formatted(clientId, redirectUri);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
