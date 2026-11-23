package bg.tusofia.unicloud.portal.web;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The app at /app and the UI package at /ui/v1 (react-hypermedia-ui spec, "The portal app" and
 * "Shared UI package"), with stand-in files from src/test/resources/static.
 */
@SpringBootTest
class AppHostingTests {

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
    void aPageOfTheAppGetsTheAppsIndex() throws Exception {
        for (String path : new String[]{"/app", "/app/", "/app/courses", "/app/teach/offerings/7"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString("<div id=\"root\">")))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        }
    }

    @Test
    void assetsAreCachedForAYearAndAMissingOneIs404() throws Exception {
        mockMvc.perform(get("/app/assets/app-test.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")));

        mockMvc.perform(get("/app/assets/no-such-file.js"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("max-age=31536000"))));
    }

    @Test
    void theUiPackageIsReadableFromAnyOrigin() throws Exception {
        mockMvc.perform(get("/ui/v1/unicloud.css").header(HttpHeaders.ORIGIN, "https://library.example"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "cross-origin"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=3600")));
        mockMvc.perform(get("/ui/v1/unicloud-ui-9.9.9.tgz"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBrowserAtTheRootGoesToTheAppAndCurlStillSeesTheReplica() throws Exception {
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/app/"));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("unicloud-portal"))
                .andExpect(jsonPath("$.replica").exists());
    }
}
