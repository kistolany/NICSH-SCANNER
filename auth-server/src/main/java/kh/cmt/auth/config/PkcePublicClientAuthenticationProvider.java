package kh.cmt.auth.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Lets the mobile app (a public client: no secret) receive and use refresh tokens.
 * <p>
 * Spring Authorization Server has two gaps for this, both closed here without touching its security checks:
 * <ol>
 *   <li>It never issues a refresh token to a client authenticated with method {@code none}. After Spring's own
 *       provider has verified the PKCE {@code code_verifier}, we hand the client back labelled
 *       {@link #PKCE_PUBLIC}, which makes it eligible. Spring's provider still does the PKCE check.</li>
 *   <li>It does not authenticate a public client on the {@code refresh_token} grant. For that grant the client
 *       is identified by {@code client_id}; the refresh token itself is the credential and it rotates on use.</li>
 * </ol>
 * The client must be registered with authentication method {@code none} either way.
 */
class PkcePublicClientAuthenticationProvider implements AuthenticationProvider {

    static final ClientAuthenticationMethod PKCE_PUBLIC = new ClientAuthenticationMethod("pkce_public");

    private final AuthenticationProvider springPublicClientProvider;
    private final RegisteredClientRepository clients;

    PkcePublicClientAuthenticationProvider(AuthenticationProvider springPublicClientProvider,
            RegisteredClientRepository clients) {
        this.springPublicClientProvider = springPublicClientProvider;
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())) {
            return null;
        }
        boolean refreshGrant = AuthorizationGrantType.REFRESH_TOKEN.getValue()
                .equals(token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE));

        RegisteredClient client;
        if (refreshGrant) {
            client = clients.findByClientId(token.getPrincipal().toString());
            if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
            }
        } else {
            // authorization_code + PKCE: Spring verifies code_verifier against the stored challenge
            Authentication verified = springPublicClientProvider.authenticate(authentication);
            client = ((OAuth2ClientAuthenticationToken) verified).getRegisteredClient();
        }
        return new OAuth2ClientAuthenticationToken(client, PKCE_PUBLIC, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
