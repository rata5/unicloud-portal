package bg.tusofia.unicloud.portal;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * The same browser hardening on every response, from each of the three filter chains
 * (operations spec, "Security headers").
 */
@SpringBootTest
class SecurityHeadersTests {

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
    void thePortalsPagesAndApi() throws Exception {
        hardened(mockMvc.perform(get("/login")));
        hardened(mockMvc.perform(get("/health")));
        hardened(mockMvc.perform(get("/api/v1/portal/me")));   // 401, still hardened
        hardened(mockMvc.perform(get("/no-such-page")));       // 404, still hardened
    }

    @Test
    void singleSignOnAndTheDirectory() throws Exception {
        hardened(mockMvc.perform(get("/.well-known/openid-configuration")));
        hardened(mockMvc.perform(get("/oauth2/jwks")));
        hardened(mockMvc.perform(get("/api/v1/directory/users/4")));   // 401, still hardened
    }

    private static void hardened(ResultActions response) throws Exception {
        response
                .andExpect(header().string("Content-Security-Policy", allOf(
                        containsString("default-src 'self'"),
                        containsString("script-src 'self'"),
                        containsString("frame-ancestors 'none'"),
                        containsString("base-uri 'self'"),
                        containsString("object-src 'none'"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }
}
