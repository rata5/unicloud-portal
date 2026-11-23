package bg.tusofia.unicloud.portal.support;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One browser for tests of the cookie session: it keeps the session cookie between requests, as
 * a real one does, signs in through /login, and calls the app's JSON API with the session's CSRF
 * token in X-CSRF-Token, as the React app does.
 */
public final class PortalBrowser {

    public static final String SESSION_COOKIE = "__Host-UNICLOUD_SSO";
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mockMvc;
    private Cookie session;
    private String csrf;

    public PortalBrowser(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** A browser signed in as {@code email}; fails the test if the sign-in does not open the app. */
    public static PortalBrowser signedIn(MockMvc mockMvc, String email, String password) throws Exception {
        PortalBrowser browser = new PortalBrowser(mockMvc);
        String redirect = browser.signIn(email, password).getResponse().getRedirectedUrl();
        assertThat(redirect).as("sign-in of " + email).isEqualTo("/app/");
        return browser;
    }

    public MvcResult signIn(String email, String password) throws Exception {
        String page = perform(MockMvcRequestBuilders.get("/login")).getResponse().getContentAsString();
        Matcher matcher = CSRF_FIELD.matcher(page);
        assertThat(matcher.find()).as("a csrf field on /login").isTrue();
        return perform(MockMvcRequestBuilders.post("/login").param("csrf", matcher.group(1)).param("email", email).param("password", password));
    }

    public MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
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

    /** GET with the app's Accept header. */
    public Answer get(String path) throws Exception {
        return answer(perform(MockMvcRequestBuilders.get(path)
                .accept(MediaType.APPLICATION_JSON)));
    }

    /** POST with the session's CSRF token, and a JSON body when {@code json} is not null. */
    public Answer post(String path, String json) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .accept(MediaType.APPLICATION_JSON).header("X-CSRF-Token", csrf());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return answer(perform(request));
    }

    public Answer put(String path, String json) throws Exception {
        return answer(perform(MockMvcRequestBuilders.put(path)
                .accept(MediaType.APPLICATION_JSON).header("X-CSRF-Token", csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)));
    }

    /** A POST without the CSRF header, as another site would send it. */
    public Answer postWithoutCsrf(String path) throws Exception {
        return answer(perform(MockMvcRequestBuilders.post(path)
                .accept(MediaType.APPLICATION_JSON)));
    }

    /** The session's CSRF token, as the app reads it from GET /api/v1/portal/me. */
    public String csrf() throws Exception {
        if (csrf == null) {
            csrf = get("/api/v1/portal/me").json().get("csrf").asString();
        }
        return csrf;
    }

    private static Answer answer(MvcResult result) throws Exception {
        return new Answer(result);
    }

    /** A response, with its JSON body read. */
    public record Answer(MvcResult result) {

        public int status() {
            return result.getResponse().getStatus();
        }

        public JsonNode json() throws Exception {
            String body = result.getResponse().getContentAsString();
            return body.isEmpty() ? JSON.nullNode() : JSON.readTree(body);
        }

        public String header(String name) {
            return result.getResponse().getHeader(name);
        }
    }
}
