package bg.tusofia.unicloud.portal.config;

import bg.tusofia.unicloud.portal.sso.infrastructure.AccessTokenDecoders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The API reference for module backends: OpenAPI 3.1 at /openapi.json and Swagger UI at
 * /api-docs (module-directory spec, "API reference"). Based on the Swagger work of PR #12.
 *
 * <p>springdoc describes the controllers (directory, staging tools, /health). The SSO endpoints
 * are the authorization server's filters, so {@link #ssoEndpoints()} adds them by hand. The
 * browser part of single sign-on ({@code /oauth2/authorize}, {@code /connect/logout}) is in the
 * discovery document and docs/sso.md; every OIDC library reads it.
 */
@Configuration
public class OpenApiConfig {

    /** HTTP Basic with the module's client id and secret, for /oauth2/token. */
    public static final String CLIENT_BASIC = "clientBasic";

    /** A client-credentials token with scope directory; Swagger UI can get one itself. */
    public static final String DIRECTORY_TOKEN = "directoryToken";

    /** The access token a module got for a signed-in user, for /userinfo. */
    public static final String USER_ACCESS_TOKEN = "userAccessToken";

    public static final String SSO_TAG = "SSO";

    @Bean
    OpenAPI portalOpenApi(@Value("${GIT_SHA:dev}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("UniCloud Portal API")
                        .version(version)
                        .description("""
                                The APIs a UniCloud module's backend calls: single sign-on tokens, \
                                the user's claims, and the read-only directory. The browser part of \
                                single sign-on is in the discovery document and in docs/sso.md.

                                **Try the directory:** register a module with the directory grant \
                                (on staging: the staging page at `/staging`), press **Authorize**, \
                                choose `directoryToken` and enter the client id and secret. Swagger \
                                UI then gets a client-credentials token for you.

                                **Unknown values mean no access:** `role` and `permissions` may gain \
                                new values. Treat one you do not know as no access."""))
                .addTagsItem(new Tag().name(SSO_TAG).description("Tokens and claims (docs/sso.md)"))
                .components(new Components()
                        .addSecuritySchemes(CLIENT_BASIC, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("The module's client id and secret"))
                        .addSecuritySchemes(DIRECTORY_TOKEN, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("A client-credentials token with scope directory")
                                .flows(new OAuthFlows().clientCredentials(new OAuthFlow()
                                        .tokenUrl("/oauth2/token")
                                        .scopes(new Scopes().addString(AccessTokenDecoders.DIRECTORY_SCOPE,
                                                "Read who is who: users, courses, faculties")))))
                        .addSecuritySchemes(USER_ACCESS_TOKEN, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("The access_token from a user's login (authorization code)")));
    }

    @Bean
    OpenApiCustomizer ssoEndpoints() {
        return openApi -> openApi
                .path("/.well-known/openid-configuration", new PathItem().get(sso(
                        "Discovery document", "The provider's endpoints and what it supports")))
                .path("/oauth2/jwks", new PathItem().get(sso(
                        "Public signing keys", "The keys that sign every token, each with its kid")))
                .path("/oauth2/token", new PathItem().post(token()))
                .path("/userinfo", new PathItem()
                        .get(userInfo())
                        .post(userInfo()));
    }

    private static Operation sso(String summary, String description) {
        return new Operation()
                .addTagsItem(SSO_TAG)
                .summary(summary)
                .description(description)
                .responses(new ApiResponses().addApiResponse("200", json("The document")));
    }

    private static Operation token() {
        Schema<?> form = new ObjectSchema()
                .addProperty("grant_type", new StringSchema()
                        ._enum(List.of("authorization_code", "client_credentials")))
                .addProperty("code", new StringSchema().description("authorization_code: the code from the callback"))
                .addProperty("redirect_uri", new StringSchema().description("authorization_code: the same callback as at login"))
                .addProperty("code_verifier", new StringSchema().description("authorization_code: the PKCE verifier"))
                .addProperty("scope", new StringSchema().description("client_credentials: directory (the default)"));
        form.setRequired(List.of("grant_type"));
        return new Operation()
                .addTagsItem(SSO_TAG)
                .summary("Exchange a code, or get a directory token")
                .description("""
                        `authorization_code`: after a user's login, gives `access_token`, `id_token`, \
                        `scope`, `token_type` and `expires_in`. `client_credentials`: for a module with \
                        the directory grant, gives an `access_token` with scope `directory`. The client \
                        authenticates with HTTP Basic only.""")
                .addSecurityItem(new SecurityRequirement().addList(CLIENT_BASIC))
                .requestBody(new RequestBody().required(true).content(new Content()
                        .addMediaType("application/x-www-form-urlencoded", new MediaType().schema(form))))
                .responses(new ApiResponses()
                        .addApiResponse("200", json("The tokens"))
                        .addApiResponse("400", json("invalid_grant, invalid_scope, invalid_request or unsupported_grant_type"))
                        .addApiResponse("401", json("invalid_client: unknown module, wrong secret, or not HTTP Basic")));
    }

    private static Operation userInfo() {
        return new Operation()
                .addTagsItem(SSO_TAG)
                .summary("The signed-in user's claims")
                .description("The same claims as the ID token, for the granted scopes, read at request time.")
                .addSecurityItem(new SecurityRequirement().addList(USER_ACCESS_TOKEN))
                .responses(new ApiResponses()
                        .addApiResponse("200", json("sub and the claims"))
                        .addApiResponse("401", json("No token, or not a user's access token")));
    }

    private static ApiResponse json(String description) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json", new MediaType().schema(new ObjectSchema())));
    }
}
