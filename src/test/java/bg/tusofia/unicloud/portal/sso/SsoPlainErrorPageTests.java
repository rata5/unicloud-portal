package bg.tusofia.unicloud.portal.sso;

import bg.tusofia.unicloud.portal.support.TestJwtKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error answers through a real server. MockMvc stops at {@code sendError}, but a servlet
 * container then dispatches to /error, which runs the security chains again: an anonymous
 * caller got 401 with no body instead of the plain 400 that docs/sso.md promises (recorded on
 * the Java staging, docs/sso-wire-contract.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SsoPlainErrorPageTests {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
    }

    @Test
    void anUnknownModuleGetsAPlain400AndNoRedirect() throws Exception {
        HttpResponse<String> response = get("/oauth2/authorize?response_type=code&client_id=no-such-module"
                + "&redirect_uri=https%3A%2F%2Fevil.example%2Fcallback&scope=openid&state=s"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void anEndpointThePortalDoesNotOfferIs404() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/oauth2/revoke"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Accept", "text/html")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
