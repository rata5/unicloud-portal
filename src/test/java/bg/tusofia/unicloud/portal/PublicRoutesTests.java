package bg.tusofia.unicloud.portal;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The routes left to the last filter chain (PublicRoutesSecurityConfig): the public ones answer,
 * and everything else is 404. The portal's old JWT login, static pages and REST routes are gone
 * (complete-java-portal task 2.6, react-hypermedia-ui task 4.3), whatever a caller sends.
 */
@SpringBootTest
class PublicRoutesTests {

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void thePublicRoutesAnswer() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        mockMvc.perform(get("/openapi.json")).andExpect(status().isOk());
    }

    @Test
    void theOldLoginRoutesAndPagesAreGone() throws Exception {
        List<String[]> old = List.of(
                new String[] {"POST", "/api/v1/auth/login"},
                new String[] {"GET", "/api/v1/auth/me"},
                new String[] {"POST", "/api/v1/auth/register"},
                new String[] {"POST", "/api/v1/auth/sso-session"},
                new String[] {"DELETE", "/api/v1/auth/sso-session"},
                new String[] {"POST", "/api/v1/sso/clients"},
                new String[] {"GET", "/api/v1/users/me"},
                new String[] {"POST", "/api/v1/students"},
                new String[] {"GET", "/api/v1/courses"},
                new String[] {"PUT", "/api/v1/courses/1"},
                new String[] {"GET", "/api/v1/offerings"},
                new String[] {"POST", "/api/v1/enrollments"},
                new String[] {"PATCH", "/api/v1/enrollments/1/cancel"},
                new String[] {"GET", "/api/v1/students/4/enrollments"},
                new String[] {"GET", "/login.html"},
                new String[] {"GET", "/profile.html"},
                new String[] {"GET", "/courses.html"},
                new String[] {"GET", "/css/app.css"},
                new String[] {"GET", "/js/api.js"},
                new String[] {"GET", "/no-such-page"});
        for (String[] route : old) {
            int plain = statusOf(route, null);
            // A Bearer header (a portal token from before) changes nothing: no chain reads it here
            int withToken = statusOf(route, "Bearer eyJhbGciOiJSUzI1NiJ9.e30.c2ln");
            assertThat(plain).as(route[0] + " " + route[1]).isEqualTo(404);
            assertThat(withToken).as(route[0] + " " + route[1] + " with a token").isEqualTo(404);
        }
    }

    private int statusOf(String[] route, String authorization) throws Exception {
        var request = request(HttpMethod.valueOf(route[0]), route[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }
}
