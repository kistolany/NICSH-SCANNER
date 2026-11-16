package kh.cmt.auth.app;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.auth.AuthProperties;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import kh.cmt.auth.account.AccountUserDetailsService;
import kh.cmt.auth.device.AppVersions;
import kh.cmt.auth.device.DeviceStore;
import kh.cmt.auth.session.SessionRevoker;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mobile app's own login screen (the design has one; the browser flow with PKCE stays for other clients):
 * {@code POST /auth/login} with username, password and the phone, {@code POST /auth/refresh}, {@code POST /auth/logout}.
 * <p>
 * Everything a browser login does still happens: the same password check, account states (inactive, locked, access
 * ended) and lockout counting; the tokens are made by the same generator and claims customizer and are stored as a login
 * session of client {@code cmt-mobile}, so refresh-token rotation and reuse detection apply exactly as for the browser
 * flow. Only app accounts may log in here (office users use the Admin Web).
 * <p>
 * <b>One phone per account</b> (step 6.3, the school's device settings): the first login binds the phone
 * ({@code deviceId}); a login from another phone is refused with {@code DEVICE_MISMATCH}, the name of the linked phone
 * and a {@code deviceChangeToken} with which that phone may ask the school for the change
 * ({@code /auth/device/change-request}). A blocked phone gets {@code DEVICE_BLOCKED}; an app older than the school's
 * minimum version gets {@code UPDATE_REQUIRED} (426, with {@code minVersion}).
 * <p>
 * A first password (made by the admin) must be changed: the answer says {@code mustChangePassword} and the app shows its
 * change-password screen before anything else ({@code POST /auth/password/change}).
 */
@Service
public class AppLoginService {

    /** The grant recorded on login sessions made here (shown in the authorization store). */
    static final AuthorizationGrantType APP_LOGIN = new AuthorizationGrantType("urn:cmt:grant-type:app-login");
    static final String MOBILE_CLIENT = "cmt-mobile";

    /**
     * {@code osVersion}, {@code publicKey} (the phone's key, made in its secure storage, for signed scans from step
     * 7.2) and {@code rooted} are optional.
     */
    public record LoginRequest(String username, String password, String deviceId, String deviceName, String platform,
            String appVersion, String osVersion, String publicKey, Boolean rooted) {
    }

    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    }

    public record LoginResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, String role,
            boolean mustChangePassword) {
    }

    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationService authorizations;
    private final JwtGenerator accessTokens;
    private final OAuth2RefreshTokenGenerator refreshTokens = new OAuth2RefreshTokenGenerator();
    private final ProviderManager passwordCheck;
    private final AccountRepository accounts;
    private final SessionRevoker sessions;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuthorizationServerContext serverContext;
    private final DeviceStore devices;

    AppLoginService(RegisteredClientRepository clients, OAuth2AuthorizationService authorizations, JwtEncoder jwtEncoder,
            OAuth2TokenCustomizer<JwtEncodingContext> claims, AccountUserDetailsService users, PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events, AccountRepository accounts, SessionRevoker sessions, JdbcClient jdbc, Clock clock,
            AuthorizationServerSettings settings, AuthProperties props, DeviceStore devices) {
        this.clients = clients;
        this.authorizations = authorizations;
        this.accounts = accounts;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.devices = devices;
        this.accessTokens = new JwtGenerator(jwtEncoder);
        this.accessTokens.setJwtCustomizer(claims);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setUserDetailsPasswordService(users);   // old bcrypt hashes become Argon2id, as at a browser login
        this.passwordCheck = new ProviderManager(provider);
        // the same events as a browser login: wrong passwords count towards the lockout, a good one resets it
        this.passwordCheck.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(events));
        this.serverContext = new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return props.issuer();
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return settings;
            }
        };
    }

    @Transactional(noRollbackFor = AppLoginException.class)   // a wrong password must still count towards the lockout
    public LoginResponse login(LoginRequest request) {
        UUID deviceId = check(request);
        Authentication user;
        try {
            user = passwordCheck.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.username().trim(),
                    request.password()));
        } catch (BadCredentialsException wrong) {
            throw new AppLoginException(AppLoginException.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        } catch (LockedException locked) {
            throw new AppLoginException(AppLoginException.ACCOUNT_LOCKED, HttpStatus.FORBIDDEN);
        } catch (DisabledException inactive) {
            throw new AppLoginException(AppLoginException.ACCOUNT_INACTIVE, HttpStatus.FORBIDDEN);
        } catch (AccountExpiredException expired) {
            throw new AppLoginException(AppLoginException.ACCESS_EXPIRED, HttpStatus.FORBIDDEN);
        } catch (AuthenticationException other) {
            throw new AppLoginException(AppLoginException.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        }
        AccountClaims account = accounts.findByUsername(user.getName())
                .orElseThrow(() -> new AppLoginException(AppLoginException.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED));
        if (!"app".equals(account.type())) {
            throw new AppLoginException(AppLoginException.NOT_AN_APP_ACCOUNT, HttpStatus.FORBIDDEN);
        }
        DeviceStore.Policy policy = devices.policy(account.schoolId());
        if (AppVersions.older(request.appVersion(), policy.minAppVersion())) {
            throw new AppLoginException(AppLoginException.UPDATE_REQUIRED, HttpStatus.UPGRADE_REQUIRED)
                    .with("minVersion", policy.minAppVersion());
        }
        if (devices.blocked(deviceId)) {
            throw new AppLoginException(AppLoginException.DEVICE_BLOCKED, HttpStatus.FORBIDDEN);
        }
        bindOrRefuse(account, phone(deviceId, request), policy);

        RegisteredClient client = mobileClient();
        Issued issued = issue(client, user, APP_LOGIN);
        OAuth2Authorization session = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(user.getName())
                .authorizationGrantType(APP_LOGIN)
                .authorizedScopes(Set.of())
                .attribute(Principal.class.getName(), user)
                .token(issued.access(), metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, issued.claims()))
                .refreshToken(issued.refresh())
                .build();
        authorizations.save(session);
        boolean mustChange = jdbc.sql("select must_change_password from account where id = :id").param("id", account.id())
                .query(Boolean.class).single();
        return new LoginResponse(issued.access().getTokenValue(), issued.refresh().getTokenValue(), "Bearer",
                expiresIn(issued.access()), account.role(), mustChange);
    }

    /** New tokens for a refresh token (rotated: the old one stops working; using it again ends the login session). */
    @Transactional(noRollbackFor = AppLoginException.class)   // a reused token's session revocation must stay
    public Tokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AppLoginException(AppLoginException.INVALID_TOKEN, HttpStatus.UNAUTHORIZED);
        }
        OAuth2Authorization session = authorizations.findByToken(refreshToken, OAuth2TokenType.REFRESH_TOKEN);
        RegisteredClient client = mobileClient();
        if (session == null || !client.getId().equals(session.getRegisteredClientId()) || session.getRefreshToken() == null
                || !session.getRefreshToken().isActive()) {
            throw new AppLoginException(AppLoginException.INVALID_TOKEN, HttpStatus.UNAUTHORIZED);
        }
        Authentication user = session.getAttribute(Principal.class.getName());
        if (user == null) {
            throw new AppLoginException(AppLoginException.INVALID_TOKEN, HttpStatus.UNAUTHORIZED);
        }
        Issued issued = issue(client, user, AuthorizationGrantType.REFRESH_TOKEN);   // re-reads the account: inactive → refused
        devices.seen(user.getName());   // "Last seen" on Admin Web › Devices
        authorizations.save(OAuth2Authorization.from(session)
                .token(issued.access(), metadata -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, issued.claims()))
                .refreshToken(issued.refresh())
                .build());
        return new Tokens(issued.access().getTokenValue(), issued.refresh().getTokenValue(), "Bearer", expiresIn(issued.access()));
    }

    /** Ends the account's login sessions (one phone per account). The access token runs out within 15 minutes. */
    @Transactional
    public void logout(UUID accountId) {
        sessions.revokeAll(accountId);
    }

    // ------------------------------------------------------------------ helpers

    private record Issued(OAuth2AccessToken access, Map<String, Object> claims, OAuth2RefreshToken refresh) {
    }

    private Issued issue(RegisteredClient client, Authentication user, AuthorizationGrantType grant) {
        DefaultOAuth2TokenContext.Builder context = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(user)
                .authorizationServerContext(serverContext)
                .authorizedScopes(Set.of())
                .authorizationGrantType(grant)
                .authorizationGrant(user);
        Jwt jwt;
        try {
            jwt = accessTokens.generate(context.tokenType(OAuth2TokenType.ACCESS_TOKEN).build());
        } catch (OAuth2AuthenticationException refused) {   // the claims customizer refuses inactive / not allowed accounts
            throw new AppLoginException(AppLoginException.INVALID_TOKEN, HttpStatus.UNAUTHORIZED);
        }
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, jwt.getTokenValue(),
                jwt.getIssuedAt(), jwt.getExpiresAt(), Set.of());
        OAuth2RefreshToken refresh = refreshTokens.generate(context.tokenType(OAuth2TokenType.REFRESH_TOKEN).build());
        return new Issued(access, jwt.getClaims(), refresh);
    }

    private long expiresIn(OAuth2AccessToken token) {
        return Duration.between(clock.instant(), token.getExpiresAt()).toSeconds();
    }

    private RegisteredClient mobileClient() {
        RegisteredClient client = clients.findByClientId(MOBILE_CLIENT);
        if (client == null) {
            throw new IllegalStateException("Client " + MOBILE_CLIENT + " is not registered");
        }
        return client;
    }

    /** All fields required; the phone id must be a UUID (the app makes one on first launch). */
    private static UUID check(LoginRequest r) {
        List<Map<String, String>> errors = new java.util.ArrayList<>();
        if (r.username() == null || r.username().isBlank()) {
            errors.add(Map.of("field", "username", "rule", "NotBlank"));
        }
        if (r.password() == null || r.password().isEmpty()) {
            errors.add(Map.of("field", "password", "rule", "NotBlank"));
        }
        UUID device = null;
        if (r.deviceId() == null || r.deviceId().isBlank()) {
            errors.add(Map.of("field", "deviceId", "rule", "NotBlank"));
        } else {
            try {
                device = UUID.fromString(r.deviceId().trim());
            } catch (IllegalArgumentException notUuid) {
                errors.add(Map.of("field", "deviceId", "rule", "Uuid"));
            }
        }
        if (!errors.isEmpty()) {
            throw new AppLoginException(AppLoginException.VALIDATION_ERROR, HttpStatus.BAD_REQUEST).with("errors", errors);
        }
        return device;
    }

    /**
     * The phone and the account: no phone linked yet → this one is linked; the linked one → its details are kept up to
     * date; another phone → moved to it when the school allows several phones or the linked one was not used for the
     * school's "automatic rebind" days, else refused with {@code DEVICE_MISMATCH}, the linked phone's name and (when
     * the school lets staff ask) a token with which this phone can ask for the change.
     */
    private void bindOrRefuse(AccountClaims account, DeviceStore.Phone phone, DeviceStore.Policy policy) {
        Optional<DeviceStore.Linked> linked = devices.linked(account.id());
        if (linked.isEmpty()) {
            devices.bind(account.id(), phone);
            devices.event(account.schoolId(), account.id(), "LINKED", null, phone.name(), null, null);
            return;
        }
        DeviceStore.Linked current = linked.get();
        if (current.deviceId().equals(phone.deviceId())) {
            devices.touch(account.id(), phone);
            return;
        }
        boolean idle = policy.autoRebindDays() != null && (current.lastSeenAt() == null
                || current.lastSeenAt().isBefore(clock.instant().minus(Duration.ofDays(policy.autoRebindDays()))));
        if (!policy.oneDevice() || idle) {
            devices.bind(account.id(), phone);
            devices.event(account.schoolId(), account.id(), idle ? "AUTO_REBIND" : "LINKED", current.name(), phone.name(), null, null);
            sessions.revokeAll(account.id());   // the old phone's login ends
            return;
        }
        throw new AppLoginException(AppLoginException.DEVICE_MISMATCH, HttpStatus.FORBIDDEN)
                .with("linkedDeviceName", current.name() == null ? "" : current.name())
                .with("deviceChangeToken", policy.changeRequests() ? devices.newChangeToken(account.id(), phone) : null);
    }

    /** What the phone says about itself, trimmed to the column sizes. */
    private static DeviceStore.Phone phone(UUID deviceId, LoginRequest r) {
        String platform = cut(r.platform(), 10);
        return new DeviceStore.Phone(deviceId, cut(r.deviceName(), 100), platform == null ? null : platform.toLowerCase(java.util.Locale.ROOT),
                cut(r.osVersion(), 40), cut(r.appVersion(), 20), cut(r.publicKey(), 2000), r.rooted());
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
