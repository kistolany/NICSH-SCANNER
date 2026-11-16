package kh.cmt.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Parallel requests with an expired access token must refresh once, or the auth server sees token theft. */
class SingleFlightAuthorizedClientManagerTest {

    static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final Authentication principal = new TestingAuthenticationToken("user", "n/a");
    final ClientRegistration registration = ClientRegistration.withRegistrationId("cmt")
            .clientId("cmt-web-bff").clientSecret("s").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/cb").authorizationUri("http://auth/authorize").tokenUri("http://auth/token")
            .build();

    final AtomicInteger refreshCalls = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    ServerOAuth2AuthorizedClientRepository repository;
    ServerWebExchange exchange;
    OAuth2AuthorizedClient storedClient;

    @BeforeEach
    void setUp() {
        WebSession session = mock(WebSession.class);
        when(session.getId()).thenReturn("session-1");
        exchange = mock(ServerWebExchange.class);
        when(exchange.getSession()).thenReturn(Mono.just(session));

        repository = mock(ServerOAuth2AuthorizedClientRepository.class);
        when(repository.loadAuthorizedClient(any(), any(), any())).thenAnswer(i -> Mono.justOrEmpty(storedClient));
        when(repository.saveAuthorizedClient(any(), any(), any())).thenReturn(Mono.empty());
    }

    /** A delegate that "refreshes" slowly: refresh-0 becomes refresh-1 and a new access token. */
    ReactiveOAuth2AuthorizedClientManager delegate(boolean failFirst) {
        return request -> Mono.defer(() -> {
            int n = refreshCalls.incrementAndGet();
            if (failFirst && failures.getAndIncrement() == 0) {
                return Mono.error(new IllegalStateException("auth server down"));
            }
            return Mono.delay(Duration.ofMillis(150)).thenReturn(client("access-" + n, "refresh-" + n, NOW.plusSeconds(900)));
        });
    }

    OAuth2AuthorizedClient client(String access, String refresh, Instant accessExpires) {
        return new OAuth2AuthorizedClient(registration, "user",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access, NOW.minusSeconds(900), accessExpires),
                new OAuth2RefreshToken(refresh, NOW.minusSeconds(900)));
    }

    OAuth2AuthorizeRequest request() {
        return OAuth2AuthorizeRequest.withClientRegistrationId("cmt").principal(principal)
                .attribute(ServerWebExchange.class.getName(), exchange).build();
    }

    @Test
    void fiveParallelRequestsWithAnExpiredTokenRefreshOnlyOnce() {
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);

        List<OAuth2AuthorizedClient> results = Flux.range(0, 5)
                .flatMap(i -> manager.authorize(request())).collectList().block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(1);
        assertThat(results).hasSize(5).allSatisfy(c -> assertThat(c.getAccessToken().getTokenValue()).isEqualTo("access-1"));
        verify(repository, times(5)).saveAuthorizedClient(any(), any(), any());   // each request stores the new tokens
    }

    @Test
    void worksWhenTheExchangeComesFromTheReactorContextAsWithTheGatewayTokenRelay() {
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);
        OAuth2AuthorizeRequest withoutExchange = OAuth2AuthorizeRequest.withClientRegistrationId("cmt").principal(principal).build();

        Flux.range(0, 5)
                .flatMap(i -> manager.authorize(withoutExchange)
                        .contextWrite(ctx -> ctx.put(ServerWebExchange.class, exchange)))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(1);
    }

    @Test
    void aRequestThatStillSeesTheOldTokensJustAfterTheRefreshReusesTheNewOnes() {
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);
        manager.authorize(request()).block(Duration.ofSeconds(5));

        // the first request's session is not saved yet, so this one still loads refresh-0
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        OAuth2AuthorizedClient late = manager.authorize(request()).block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(1);   // refresh-0 was NOT used a second time
        assertThat(late.getAccessToken().getTokenValue()).isEqualTo("access-1");
    }

    @Test
    void aValidTokenIsNotRefreshed() {
        storedClient = client("access-0", "refresh-0", NOW.plusSeconds(600));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);

        OAuth2AuthorizedClient result = manager.authorize(request()).block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(0);
        assertThat(result.getAccessToken().getTokenValue()).isEqualTo("access-0");
    }

    @Test
    void aTokenExpiringWithinTheNextMinuteIsRefreshedEarly() {
        storedClient = client("access-0", "refresh-0", NOW.plusSeconds(30));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);
        manager.authorize(request()).block(Duration.ofSeconds(5));
        assertThat(refreshCalls).hasValue(1);
    }

    @Test
    void aFailedRefreshIsNotRememberedSoTheNextRequestCanTryAgain() {
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        var manager = new SingleFlightAuthorizedClientManager(delegate(true), repository, clock);

        assertThat(manager.authorize(request()).onErrorReturn(client("x", "x", NOW)).block(Duration.ofSeconds(5))
                .getAccessToken().getTokenValue()).isEqualTo("x");
        OAuth2AuthorizedClient second = manager.authorize(request()).block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(2);
        assertThat(second.getAccessToken().getTokenValue()).isEqualTo("access-2");
    }

    @Test
    void aNewRefreshTokenMeansANewRefreshIsAllowed() {
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);
        manager.authorize(request()).block(Duration.ofSeconds(5));

        storedClient = client("access-1", "refresh-1", NOW.minusSeconds(1));   // session saved, then expired again
        OAuth2AuthorizedClient next = manager.authorize(request()).block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(2);
        assertThat(next.getAccessToken().getTokenValue()).isEqualTo("access-2");
    }

    @Test
    void sessionsAreIndependent() {
        storedClient = client("access-0", "refresh-0", NOW.minusSeconds(5));
        var manager = new SingleFlightAuthorizedClientManager(delegate(false), repository, clock);
        manager.authorize(request()).block(Duration.ofSeconds(5));

        WebSession other = mock(WebSession.class);
        when(other.getId()).thenReturn("session-2");
        when(exchange.getSession()).thenReturn(Mono.just(other));
        manager.authorize(request()).block(Duration.ofSeconds(5));

        assertThat(refreshCalls).hasValue(2);   // same refresh token value in another session is a different login
    }
}
