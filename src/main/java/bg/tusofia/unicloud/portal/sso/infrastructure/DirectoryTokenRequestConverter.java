package bg.tusofia.unicloud.portal.sso.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ClientCredentialsAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code grant_type=client_credentials} at /oauth2/token, for the directory API only
 * (module-directory spec):
 * <ul>
 *   <li>a module granted {@code directory} at registration gets a token with scope
 *       {@code directory}, whether it asks for that scope or for none;</li>
 *   <li>any other scope, and any module without the grant, gets 400 {@code invalid_scope}.</li>
 * </ul>
 * Modules without the grant are not even registered for the grant type, so without this check
 * the library would answer {@code unauthorized_client}. The client is authenticated before this
 * runs (HTTP Basic), so an unknown client or a wrong secret is still 401 {@code invalid_client}.
 */
public final class DirectoryTokenRequestConverter implements AuthenticationConverter {

    private final AuthenticationConverter library = new OAuth2ClientCredentialsAuthenticationConverter();

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()
                .equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))) {
            return null;
        }
        if (!(library.convert(request) instanceof OAuth2ClientCredentialsAuthenticationToken token)
                || !(token.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)) {
            return null;
        }

        RegisteredClient registered = client.getRegisteredClient();
        boolean granted = registered != null && registered.getScopes().contains(AccessTokenDecoders.DIRECTORY_SCOPE);
        Set<String> asked = token.getScopes();
        if (!granted) {
            throw invalidScope("This module has no access to the directory API");
        }
        if (asked.isEmpty()) {
            // A LinkedHashSet: the library stores the scopes as JSON and reads back only plain collections
            return new OAuth2ClientCredentialsAuthenticationToken(
                    client, new LinkedHashSet<>(Set.of(AccessTokenDecoders.DIRECTORY_SCOPE)), token.getAdditionalParameters());
        }
        if (!asked.equals(Set.of(AccessTokenDecoders.DIRECTORY_SCOPE))) {
            throw invalidScope("With client_credentials the only scope is " + AccessTokenDecoders.DIRECTORY_SCOPE);
        }
        return token;
    }

    private static OAuth2AuthenticationException invalidScope(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE, description, null));
    }
}
