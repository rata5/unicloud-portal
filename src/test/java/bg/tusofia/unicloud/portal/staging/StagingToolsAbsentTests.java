package bg.tusofia.unicloud.portal.staging;

import bg.tusofia.unicloud.portal.support.PortalBrowser;
import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import bg.tusofia.unicloud.portal.support.TestUsers;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Without the staging profile (production) the staging tools do not exist: their routes answer
 * 404 to everyone, admins included (staging-tools spec).
 */
@SpringBootTest
class StagingToolsAbsentTests {

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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter, springSecurityFilterChain)
                .build();
    }

    @Test
    void anonymousCallersFindNothing() throws Exception {
        mockMvc.perform(get("/staging")).andExpect(status().isNotFound());
        mockMvc.perform(get("/staging/staging.js")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/staging/users")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/staging/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"x@tu-sofia.bg\", \"password\": \"long-enough\", "
                                + "\"firstName\": \"X\", \"lastName\": \"Y\", \"role\": \"ADMIN\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evenASignedInAdminFindsNothing() throws Exception {
        String email = new TestUsers(userRepository, passwordEncoder, jdbc).staff("ADMIN", null).email();
        PortalBrowser admin = PortalBrowser.signedIn(mockMvc, email, TestUsers.PASSWORD);

        assertThat(admin.get("/api/v1/staging/roles").status()).isEqualTo(404);
        assertThat(admin.perform(get("/staging")).getResponse().getStatus()).isEqualTo(404);
    }
}
