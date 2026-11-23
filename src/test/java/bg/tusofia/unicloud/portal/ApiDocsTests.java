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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API reference for module backends (module-directory spec, "API reference"): OpenAPI 3.1 at
 * /openapi.json and Swagger UI at /api-docs, both without a token. Based on PR #12's tests.
 * Without the staging profile, so the staging tools must be absent here.
 */
@SpringBootTest
class ApiDocsTests {

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
    void theReferenceDescribesWhatAModuleCalls() throws Exception {
        mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi", startsWith("3.1")))
                .andExpect(jsonPath("$.info.title").value("UniCloud Portal API"))
                // The SSO endpoints, added by hand
                .andExpect(jsonPath("$.paths['/.well-known/openid-configuration'].get").exists())
                .andExpect(jsonPath("$.paths['/oauth2/jwks'].get").exists())
                .andExpect(jsonPath("$.paths['/oauth2/token'].post.security[0].clientBasic").isArray())
                .andExpect(jsonPath("$.paths['/userinfo'].get.security[0].userAccessToken").isArray())
                .andExpect(jsonPath("$.paths['/userinfo'].post").exists())
                // The directory, with its token
                .andExpect(jsonPath("$.paths['/api/v1/directory/users/{sub}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/directory/faculties/{code}/students'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/directory/users/{sub}'].get.security[0].directoryToken[0]")
                        .value("directory"))
                .andExpect(jsonPath("$.components.securitySchemes.directoryToken.flows.clientCredentials.tokenUrl")
                        .value("/oauth2/token"))
                .andExpect(jsonPath("$.paths['/health'].get").exists())
                // Not the portal's own API, and no staging tools outside staging
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/courses']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/staging/users']").doesNotExist());
    }

    @Test
    void swaggerUiOpensWithoutATokenAndRunsUnderTheContentSecurityPolicy() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().is3xxRedirection());

        String page = mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("swagger-ui");
        // script-src 'self': every script must come from a file
        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>").matcher(page).find())
                .as("an inline <script> in Swagger UI's page")
                .isFalse();

        mockMvc.perform(get("/openapi.json/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi.json"));
    }

    @Test
    void theApiItselfStillNeedsItsToken() throws Exception {
        mockMvc.perform(get("/api/v1/directory/users/4"))
                .andExpect(status().isUnauthorized());
    }
}
