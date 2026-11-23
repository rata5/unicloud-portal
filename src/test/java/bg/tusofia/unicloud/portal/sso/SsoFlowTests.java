package bg.tusofia.unicloud.portal.sso;

import bg.tusofia.unicloud.portal.support.PortalBrowser;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import bg.tusofia.unicloud.portal.support.TestUsers;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.RequestContextFilter;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Single sign-on end to end, as a module would use it (docs/sso.md): register the module, send
 * the browser to /oauth2/authorize, log in on the portal, get the code at the callback, exchange
 * it server to server, read the ID token and /userinfo, log out.
 *
 * <p>Requests go through Spring Session's filter and the real security filter chains, so the SSO
 * session is stored in the database exactly as in production. The user signs in on the portal's
 * own page, /login, as a browser does; modules are registered by a signed-in admin through the
 * app's API. Each test makes its own users (TestUsers).
 */
/*
 * Production rules: callbacks must be registered and on an allowed host. The test modules live
 * under *.example; localhost is listed only to test the http exception.
 */
@SpringBootTest(properties = "app.sso.allowed-callback-hosts=*.example,localhost")
class SsoFlowTests {

    private static final String SESSION_COOKIE = PortalBrowser.SESSION_COOKIE;
    private static final String PASSWORD = TestUsers.PASSWORD;
    private static final String STATE = "state-123";
    private static final String NONCE = "nonce-456";

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

    @Autowired
    private RegisteredClientRepository clientRepository;

    private MockMvc mockMvc;
    private TestUsers users;
    private TestUsers.Created student;
    private String facultyNumber;
    private PortalBrowser admin;

    @BeforeEach
    void setUp() {
        // RequestContextFilter runs before Spring Security in the real server too (ModuleLogins)
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter, new RequestContextFilter(), springSecurityFilterChain)
                .build();
        users = new TestUsers(userRepository, passwordEncoder, jdbc);
        facultyNumber = TestUsers.facultyNumber();
        student = users.student("Иван", "Стоянов", facultyNumber, 2);
    }

    @Test
    void moduleLogsTheUserInThroughThePortal() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();

        // 1. The module sends the browser to the portal; without a session it lands on the sign-in page
        PortalBrowser browser = new PortalBrowser(mockMvc);
        MockHttpServletResponse toLogin = browser.perform(authorize(client, pkce)).getResponse();
        assertThat(toLogin.getStatus()).isEqualTo(302);
        assertThat(toLogin.getRedirectedUrl()).endsWith("/login?sso=1");
        Cookie session = toLogin.getCookie(SESSION_COOKIE);
        assertThat(session).isNotNull();
        assertThat(toLogin.getHeader(HttpHeaders.SET_COOKIE))
                .contains("HttpOnly").contains("Secure").contains("SameSite=Lax");

        // 2. The user signs in there; the portal continues the waiting login
        MockHttpServletResponse signedIn = browser.signIn(student.email(), PASSWORD).getResponse();
        String continueUrl = signedIn.getRedirectedUrl();
        assertThat(continueUrl).contains("/oauth2/authorize?");
        Cookie loggedIn = signedIn.getCookie(SESSION_COOKIE);
        assertThat(loggedIn).isNotNull();
        assertThat(loggedIn.getValue()).as("a new session id at sign-in").isNotEqualTo(session.getValue());

        // 3. Back at /oauth2/authorize the portal sends the code to the registered callback
        MockHttpServletResponse atAuthorize = browser.perform(get(URI.create(continueUrl)).accept(MediaType.TEXT_HTML))
                .getResponse();
        assertThat(atAuthorize.getStatus()).isEqualTo(302);
        String callback = atAuthorize.getRedirectedUrl();
        assertThat(callback).startsWith(client.redirectUri() + "?");
        var params = UriComponentsBuilder.fromUriString(callback).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo(STATE);
        String code = params.getFirst("code");
        assertThat(code).isNotBlank();

        // 4. The module's backend exchanges the code
        JsonNode tokens = json.readTree(mockMvc.perform(exchange(client, code, pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode idToken = payload(tokens.get("id_token").asString());
        assertThat(idToken.get("sub").asString()).isEqualTo(student.sub());
        assertThat(idToken.get("aud").toString()).contains(client.clientId());
        assertThat(idToken.get("nonce").asString()).isEqualTo(NONCE);
        assertThat(idToken.get("email").asString()).isEqualTo(student.email());
        assertThat(idToken.get("role").asString()).isEqualTo("STUDENT");
        assertThat(idToken.get("permissions").toString()).contains("ENROLLMENT_REQUEST_CREATE");
        assertThat(idToken.get("student").get("faculty_number").asString()).isEqualTo(facultyNumber);
        // A student's faculty comes from the specialty (Компютърни науки is FCST's, V5)
        assertThat(idToken.get("faculty").get("code").asString()).isEqualTo("FCST");
        assertThat(idToken.get("faculty").get("name").asString())
                .isEqualTo("Факултет по компютърни системи и технологии");

        // 5. The module's access token reads /userinfo, but not the portal's own API
        String accessToken = tokens.get("access_token").asString();
        mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(student.sub()))
                .andExpect(jsonPath("$.family_name").value("Стоянов"))
                .andExpect(jsonPath("$.student.study_year").value(2))
                .andExpect(jsonPath("$.faculty.code").value("FCST"));
        mockMvc.perform(get("/api/v1/portal/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        // ...and not the directory, which wants the module's own client-credentials token
        mockMvc.perform(get("/api/v1/directory/users/" + student.sub()).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        // 6. The code works once only. Replaying it also revokes the tokens it gave, in case the
        //    first exchange was the attacker's
        mockMvc.perform(exchange(client, code, pkce.verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        // 7. A second module login while the SSO session lives needs no password
        Pkce second = new Pkce();
        assertThat(browser.perform(authorize(client, second)).getResponse().getRedirectedUrl())
                .startsWith(client.redirectUri() + "?code=");

        // 8. The portal's "Изход" ends the SSO session: the next module login asks for the password
        String csrf = browser.csrf();
        assertThat(browser.perform(post("/logout").param("csrf", csrf)).getResponse().getRedirectedUrl())
                .isEqualTo("/login");
        assertThat(browser.perform(authorize(client, new Pkce())).getResponse().getRedirectedUrl())
                .endsWith("/login?sso=1");
    }

    @Test
    void moduleLogoutEndsTheSsoSession() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        Login login = login(client, pkce, "openid profile email unicloud", student.email());
        String idToken = json.readTree(mockMvc.perform(exchange(client, login.code(), pkce.verifier()))
                .andReturn().getResponse().getContentAsString()).get("id_token").asString();

        // The module sends the browser to the portal's logout with the ID token it holds
        MockHttpServletResponse loggedOut = login.browser().perform(get("/connect/logout")
                        .queryParam("id_token_hint", idToken)
                        .queryParam("client_id", client.clientId())
                        .queryParam("post_logout_redirect_uri", logoutUri(client.redirectUri()))
                        .queryParam("state", STATE))
                .getResponse();
        assertThat(loggedOut.getStatus()).isEqualTo(302);
        assertThat(loggedOut.getRedirectedUrl()).isEqualTo(logoutUri(client.redirectUri()) + "?state=" + STATE);

        // The next module login asks for the password again
        assertThat(login.browser().perform(authorize(client, new Pkce())).getResponse().getRedirectedUrl())
                .endsWith("/login?sso=1");
    }

    @Test
    void codeNeedsTheVerifierThatStartedTheLogin() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        String code = loginAndGetCode(client, pkce);

        mockMvc.perform(exchange(client, code, new Pkce().verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void codeNeedsTheClientSecret() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        String code = loginAndGetCode(client, pkce);
        Client wrongSecret = new Client(client.clientId(), "not-the-secret", client.redirectUri());

        mockMvc.perform(exchange(wrongSecret, code, pkce.verifier()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void unregisteredCallbackIsRefusedWithoutARedirect() throws Exception {
        Client client = registerClient();
        Client attacker = new Client(client.clientId(), client.secret(), "https://evil.example/callback");

        MvcResult result = mockMvc.perform(authorize(attacker, new Pkce()))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void callbackMustMatchExactly() throws Exception {
        Client client = registerClient();
        Client longer = new Client(client.clientId(), client.secret(), client.redirectUri() + "/../admin");

        mockMvc.perform(authorize(longer, new Pkce()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void callbackOnAHostNoLongerAllowedIsRefused() throws Exception {
        // Registered back when its host was allowed; the host has since left the config file
        String clientId = "module-" + shortId();
        String redirectUri = "https://" + clientId + ".elsewhere.org/auth/callback";
        clientRepository.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret("{noop}" + UUID.randomUUID())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build());
        Client client = new Client(clientId, "unused", redirectUri);

        MvcResult result = mockMvc.perform(authorize(client, new Pkce(), "openid"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void callbackHostMustBeInTheConfigFile() throws Exception {
        PortalBrowser.Answer refused = admin().post(MODULES,
                clientJson("module-" + shortId(), "https://module.elsewhere.org/auth/callback"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.json().get("message").asString()).contains("sso-callback-hosts");
    }

    @Test
    void loginWithoutPkceIsRefused() throws Exception {
        Client client = registerClient();
        String withoutPkce = "/oauth2/authorize?response_type=code&scope=openid&state=" + STATE
                + "&client_id=" + client.clientId() + "&redirect_uri=" + client.redirectUri();

        String location = mockMvc.perform(get(withoutPkce).accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith(client.redirectUri() + "?error=invalid_request");
    }

    @Test
    void moduleGetsOnlyTheClaimsItsScopesAllow() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        String code = login(client, pkce, "openid email", student.email()).code();

        JsonNode idToken = payload(json.readTree(mockMvc.perform(exchange(client, code, pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id_token").asString());
        assertThat(idToken.get("email").asString()).isEqualTo(student.email());
        assertThat(idToken.has("family_name")).as("profile not asked for").isFalse();
        assertThat(idToken.has("role")).as("unicloud not asked for").isFalse();
        assertThat(idToken.has("student")).as("unicloud not asked for").isFalse();
        assertThat(idToken.has("faculty")).as("unicloud not asked for").isFalse();
    }

    @Test
    void aBearerTokenCannotStandInForTheSsoSession() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        String code = loginAndGetCode(client, pkce);
        String moduleAccessToken = json.readTree(mockMvc.perform(exchange(client, code, pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("access_token").asString();

        // A module's token gets no code: only the browser's session does
        assertThat(mockMvc.perform(authorize(client, new Pkce())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + moduleAccessToken))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl()).endsWith("/login?sso=1");
    }

    @Test
    void reservedIdsCannotBeAClientId() throws Exception {
        // Every unicloud-* id is the portal's, and an all-digit id looks like a user's sub
        for (String reserved : List.of("unicloud-api", "unicloud-directory", "unicloud-" + shortId(), "12345")) {
            PortalBrowser.Answer refused = admin().post(MODULES, clientJson(reserved, "https://api.example/auth/callback"));
            assertThat(refused.status()).as(reserved).isEqualTo(400);
            assertThat(refused.json().get("message").asString()).isEqualTo("clientId: '" + reserved + "' is reserved");
        }
    }

    @Test
    void userDisabledAfterLoginGetsNoTokens() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        String code = loginAndGetCode(client, pkce);

        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", student.id());
        mockMvc.perform(exchange(client, code, pkce.verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.id_token").doesNotExist());
    }

    @Test
    void onlyAnAdminRegistersAClient() throws Exception {
        PortalBrowser asStudent = PortalBrowser.signedIn(mockMvc, student.email(), PASSWORD);
        assertThat(asStudent.post(MODULES, clientJson("module-" + shortId(), "https://module.example/auth/callback"))
                .status()).isEqualTo(403);
        // ...and nobody without a session
        assertThat(new PortalBrowser(mockMvc).postWithoutCsrf(MODULES).status()).isIn(401, 403);
    }

    @Test
    void callbackMustBeHttpsExceptOnLocalhost() throws Exception {
        assertThat(admin().post(MODULES, clientJson("module-" + shortId(), "http://module.example/auth/callback"))
                .status()).isEqualTo(400);
        assertThat(admin().post(MODULES, clientJson("module-" + shortId(), "http://localhost:3000/auth/callback"))
                .status()).isEqualTo(201);
    }

    @Test
    void clientIdIsRegisteredOnce() throws Exception {
        Client client = registerClient();

        assertThat(admin().post(MODULES, clientJson(client.clientId(), client.redirectUri())).status()).isEqualTo(409);
    }

    @Test
    void discoveryAndKeysArePublic() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").exists())
                .andExpect(jsonPath("$.authorization_endpoint").exists())
                .andExpect(jsonPath("$.token_endpoint").exists())
                .andExpect(jsonPath("$.userinfo_endpoint").exists())
                .andExpect(jsonPath("$.end_session_endpoint").exists());

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").exists())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void discoveryListsOnlyWhatThePortalServes() throws Exception {
        JsonNode discovery = json.readTree(mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> fields = discovery.properties().stream().map(java.util.Map.Entry::getKey).sorted().toList();
        assertThat(fields).containsExactly(
                "authorization_endpoint", "code_challenge_methods_supported", "end_session_endpoint",
                "grant_types_supported", "id_token_signing_alg_values_supported", "issuer", "jwks_uri",
                "response_types_supported", "scopes_supported", "subject_types_supported",
                "token_endpoint", "token_endpoint_auth_methods_supported", "userinfo_endpoint");
        assertThat(discovery.get("grant_types_supported").toString())
                .isEqualTo("[\"authorization_code\",\"client_credentials\"]");
        assertThat(discovery.get("token_endpoint_auth_methods_supported").toString()).isEqualTo("[\"client_secret_basic\"]");
        assertThat(discovery.get("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
        assertThat(discovery.get("scopes_supported").toString())
                .isEqualTo("[\"openid\",\"profile\",\"email\",\"unicloud\",\"directory\"]");
        String issuer = discovery.get("issuer").asString();
        for (String endpoint : List.of("authorization_endpoint", "token_endpoint", "jwks_uri",
                "userinfo_endpoint", "end_session_endpoint")) {
            assertThat(discovery.get(endpoint).asString()).as(endpoint).startsWith(issuer);
        }

        JsonNode oauthMetadata = json.readTree(mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(oauthMetadata.has("revocation_endpoint")).isFalse();
        assertThat(oauthMetadata.has("introspection_endpoint")).isFalse();
        assertThat(oauthMetadata.get("grant_types_supported").toString())
                .isEqualTo("[\"authorization_code\",\"client_credentials\"]");
    }

    @Test
    void endpointsThePortalDoesNotOfferAnswer404() throws Exception {
        for (String path : List.of("/oauth2/revoke", "/oauth2/introspect", "/oauth2/device_authorization")) {
            mockMvc.perform(post(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void anotherResponseTypeGetsAPlain400() throws Exception {
        Client client = registerClient();

        Pkce pkce = new Pkce();
        MvcResult result = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "token")
                        .queryParam("client_id", client.clientId())
                        .queryParam("redirect_uri", client.redirectUri())
                        .queryParam("scope", "openid")
                        .queryParam("state", STATE)
                        .queryParam("code_challenge", pkce.challenge())
                        .queryParam("code_challenge_method", "S256")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
    }

    @Test
    void loginWithoutOpenidIsSentBackToTheModule() throws Exception {
        Client client = registerClient();

        String location = mockMvc.perform(authorize(client, new Pkce(), "profile email"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith(client.redirectUri() + "?error=invalid_request");
        assertThat(location).contains("state=" + STATE);
    }

    @Test
    void accessTokensAreTypedAndNameTheirModule() throws Exception {
        Client client = registerClient();
        Pkce pkce = new Pkce();
        String code = loginAndGetCode(client, pkce);
        JsonNode tokens = json.readTree(mockMvc.perform(exchange(client, code, pkce.verifier()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String accessToken = tokens.get("access_token").asString();

        assertThat(jwtHeader(accessToken).get("typ").asString()).isEqualTo("at+jwt");
        assertThat(payload(accessToken).get("client_id").asString()).isEqualTo(client.clientId());
        assertThat(jwtHeader(tokens.get("id_token").asString()).has("typ"))
                .as("ID tokens carry no typ, so a module can tell the two apart").isFalse();

        mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    // ---- helpers ----

    private record Client(String clientId, String secret, String redirectUri) {
    }

    /** The app's route for registering a module (an admin's page, Модули). */
    private static final String MODULES = "/api/v1/portal/admin/modules";

    /** A browser signed in as a new admin, made once per test. */
    private PortalBrowser admin() throws Exception {
        if (admin == null) {
            admin = PortalBrowser.signedIn(mockMvc, users.staff("ADMIN", null).email(), PASSWORD);
        }
        return admin;
    }

    private Client registerClient() throws Exception {
        String clientId = "module-" + shortId();
        String redirectUri = "https://" + clientId + ".example/auth/callback";

        PortalBrowser.Answer registered = admin().post(MODULES, clientJson(clientId, redirectUri));
        assertThat(registered.status()).as("registration of " + clientId).isEqualTo(201);
        return new Client(clientId, registered.json().get("clientSecret").asString(), redirectUri);
    }

    private record Login(PortalBrowser browser, String code) {
    }

    /** A module login in a new browser: the module sends it to the portal, the user signs in. */
    private Login login(Client client, Pkce pkce, String scope, String email) throws Exception {
        PortalBrowser browser = new PortalBrowser(mockMvc);
        browser.perform(authorize(client, pkce, scope));
        String resume = browser.signIn(email, PASSWORD).getResponse().getRedirectedUrl();
        assertThat(resume).as("the sign-in continues the module's login").contains("/oauth2/authorize?");
        String callback = browser.perform(get(URI.create(resume)).accept(MediaType.TEXT_HTML))
                .getResponse().getRedirectedUrl();
        String code = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");
        assertThat(code).as("code in " + callback).isNotBlank();
        return new Login(browser, code);
    }

    private String loginAndGetCode(Client client, Pkce pkce) throws Exception {
        return login(client, pkce, "openid profile email unicloud", student.email()).code();
    }

    private static MockHttpServletRequestBuilder authorize(Client client, Pkce pkce) {
        return authorize(client, pkce, "openid profile email unicloud");
    }

    private static MockHttpServletRequestBuilder authorize(Client client, Pkce pkce, String scope) {
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", client.clientId())
                .queryParam("redirect_uri", client.redirectUri())
                .queryParam("scope", scope)
                .queryParam("state", STATE)
                .queryParam("nonce", NONCE)
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .accept(MediaType.TEXT_HTML);
    }

    private static MockHttpServletRequestBuilder exchange(Client client, String code, String verifier) {
        return post("/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
                        (client.clientId() + ":" + client.secret()).getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", client.redirectUri())
                .param("code_verifier", verifier);
    }

    private static String clientJson(String clientId, String redirectUri) {
        return """
                {"clientId": "%s", "name": "Test module", "redirectUris": ["%s"],
                 "postLogoutRedirectUris": ["%s"]}
                """.formatted(clientId, redirectUri, logoutUri(redirectUri));
    }

    private static String logoutUri(String redirectUri) {
        return redirectUri.replace("/auth/callback", "/logged-out");
    }

    private JsonNode jwtHeader(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
    }

    private JsonNode payload(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
