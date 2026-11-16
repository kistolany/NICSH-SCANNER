package kh.cmt.auth.client;

import java.time.Duration;
import java.util.List;
import kh.cmt.auth.AuthProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Keeps the three OAuth2 clients in the database in line with configuration on every start:
 * <ul>
 *   <li>{@code cmt-mobile}: public client, Authorization Code + PKCE (S256 required), rotating refresh tokens</li>
 *   <li>{@code cmt-web-bff}: confidential client used by the web BFF, Authorization Code + PKCE</li>
 *   <li>{@code cmt-jobs}: Client Credentials for integrations (payroll export...)</li>
 * </ul>
 */
@Component
@Order(2)
class ClientBootstrap implements ApplicationRunner {

    static final String MOBILE = "cmt-mobile";
    static final String WEB_BFF = "cmt-web-bff";
    static final String JOBS = "cmt-jobs";

    private final RegisteredClientRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;

    ClientBootstrap(RegisteredClientRepository repository, PasswordEncoder passwordEncoder, AuthProperties props) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        upsert(mobile());
        upsert(webBff());
        upsert(jobs());
    }

    /** Insert, or update in place (same id) when the client already exists. */
    private void upsert(RegisteredClient wanted) {
        RegisteredClient existing = repository.findByClientId(wanted.getClientId());
        RegisteredClient toSave = existing == null ? wanted
                : RegisteredClient.from(wanted).id(existing.getId()).build();
        repository.save(toSave);
    }

    private RegisteredClient mobile() {
        RegisteredClient.Builder builder = RegisteredClient.withId(java.util.UUID.randomUUID().toString())
                .clientId(MOBILE)
                .clientName("CMT Staff App")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(tokens(props.tokens().mobileRefreshTtl()));
        props.clients().mobile().redirectUris().forEach(builder::redirectUri);
        return builder.build();
    }

    private RegisteredClient webBff() {
        RegisteredClient.Builder builder = RegisteredClient.withId(java.util.UUID.randomUUID().toString())
                .clientId(WEB_BFF)
                .clientName("CMT Admin Web (BFF)")
                .clientSecret(passwordEncoder.encode(props.clients().webBff().secret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(tokens(props.tokens().webRefreshTtl()));
        props.clients().webBff().redirectUris().forEach(builder::redirectUri);
        props.clients().webBff().postLogoutRedirectUris().forEach(builder::postLogoutRedirectUri);
        return builder.build();
    }

    private RegisteredClient jobs() {
        return RegisteredClient.withId(java.util.UUID.randomUUID().toString())
                .clientId(JOBS)
                .clientName("CMT Jobs & Integrations")
                .clientSecret(passwordEncoder.encode(props.clients().jobs().secret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(s -> s.addAll(List.of("jobs")))
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(props.tokens().accessTtl()).build())
                .build();
    }

    /** 15-min access token; refresh token rotates on every use (reuseRefreshTokens=false). */
    private TokenSettings tokens(Duration refreshTtl) {
        return TokenSettings.builder()
                .accessTokenTimeToLive(props.tokens().accessTtl())
                .refreshTokenTimeToLive(refreshTtl)
                .reuseRefreshTokens(false)
                .build();
    }
}
