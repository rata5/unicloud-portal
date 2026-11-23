package bg.tusofia.unicloud.portal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The request log line that docs/slo.md measures (operations spec, "Service levels are measured"). */
class RequestLogFilterTests {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void listen() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void stopListening() {
        logger.detachAppender(lines);
    }

    @Test
    void oneLinePerRequestWithTheFieldsAndNoSecrets() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/enrollments");
        request.setQueryString("code=secret-code&password=secret-password");
        request.addHeader("Authorization", "Bearer secret-token");
        request.setContent("{\"password\": \"secret-password\"}".getBytes());

        new RequestLogFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(201));

        assertThat(lines.list).hasSize(1);
        ILoggingEvent line = lines.list.getFirst();
        Map<String, Object> fields = fields(line);
        assertThat(fields).containsEntry("route_group", "enrollment")
                .containsEntry("method", "POST")
                .containsEntry("path", "/api/v1/enrollments")
                .containsEntry("status", 201)
                .containsKeys("duration_ms", "replica");
        assertThat(line.getFormattedMessage() + fields).doesNotContain("secret");
    }

    @Test
    void aFailedRequestIsLoggedAs500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");

        assertThatThrownBy(() -> new RequestLogFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(fields(lines.list.getFirst()))
                .containsEntry("route_group", "sso-token")
                .containsEntry("status", 500);
    }

    @Test
    void healthChecksAreNotLogged() throws Exception {
        new RequestLogFilter().doFilter(new MockHttpServletRequest("GET", "/health"),
                new MockHttpServletResponse(), (req, res) -> { });

        assertThat(lines.list).isEmpty();
    }

    @Test
    void routeGroupsMatchTheServiceLevels() {
        assertThat(RequestLogFilter.routeGroup("/login")).isEqualTo("sign-in");
        assertThat(RequestLogFilter.routeGroup("/api/v1/auth/login")).isEqualTo("sign-in");
        assertThat(RequestLogFilter.routeGroup("/api/v1/users/me")).isEqualTo("profile");
        assertThat(RequestLogFilter.routeGroup("/api/v1/portal/profile")).isEqualTo("profile");
        assertThat(RequestLogFilter.routeGroup("/api/v1/offerings")).isEqualTo("catalogue");
        assertThat(RequestLogFilter.routeGroup("/api/v1/portal/courses")).isEqualTo("catalogue");
        assertThat(RequestLogFilter.routeGroup("/api/v1/portal/courses/7/enroll")).isEqualTo("enrollment");
        assertThat(RequestLogFilter.routeGroup("/api/v1/portal/enrollments/7/cancel")).isEqualTo("enrollment");
        assertThat(RequestLogFilter.routeGroup("/api/v1/portal/teach/enrollments/7/reject")).isEqualTo("enrollment");
        assertThat(RequestLogFilter.routeGroup("/api/v1/portal/students/4")).isEqualTo("profile");
        assertThat(RequestLogFilter.routeGroup("/api/v1/students/4/enrollments")).isEqualTo("enrollment");
        assertThat(RequestLogFilter.routeGroup("/oauth2/token")).isEqualTo("sso-token");
        assertThat(RequestLogFilter.routeGroup("/oauth2/authorize")).isEqualTo("sso");
        assertThat(RequestLogFilter.routeGroup("/userinfo")).isEqualTo("sso");
        assertThat(RequestLogFilter.routeGroup("/api/v1/directory/users/4")).isEqualTo("directory");
        assertThat(RequestLogFilter.routeGroup("/api/v1/staging/users")).isEqualTo("staging");
        assertThat(RequestLogFilter.routeGroup("/css/app.css")).isEqualTo("other");
    }

    private static Map<String, Object> fields(ILoggingEvent line) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (KeyValuePair pair : line.getKeyValuePairs()) {
            fields.put(pair.key, pair.value);
        }
        return fields;
    }
}
