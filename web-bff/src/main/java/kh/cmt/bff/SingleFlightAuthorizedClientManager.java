package kh.cmt.bff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Makes sure an access token is refreshed <b>once</b>, however many requests find it expired at the same time.
 * <p>
 * Why this matters: the auth server rotates refresh tokens and treats a refresh token used twice as theft
 * (the whole login session is revoked). A page that fires five API calls just after the 15-minute access
 * token expired would otherwise refresh five times with the same refresh token.
 * <p>
 * Refreshes are shared per (browser session, refresh token). The result is kept for {@code GRACE} so that a
 * request that started before the refresh was written to the session (it still sees the old tokens) reuses
 * the new tokens instead of refreshing again with the old, already used, refresh token.
 * <p>
 * Limit: this works inside one BFF instance. With several instances, use sticky sessions (or add a shared lock).
 */
class SingleFlightAuthorizedClientManager implements ReactiveOAuth2AuthorizedClientManager {

    static final Duration GRACE = Duration.ofSeconds(30);
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(SingleFlightAuthorizedClientManager.class);

    private record Shared(Mono<OAuth2AuthorizedClient> result, Instant created) {
    }

    private final ReactiveOAuth2AuthorizedClientManager delegate;
    private final ServerOAuth2AuthorizedClientRepository repository;
    private final Clock clock;
    private final ConcurrentMap<String, Shared> refreshes = new ConcurrentHashMap<>();

    SingleFlightAuthorizedClientManager(ReactiveOAuth2AuthorizedClientManager delegate,
            ServerOAuth2AuthorizedClientRepository repository, Clock clock) {
        this.delegate = delegate;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Mono<OAuth2AuthorizedClient> authorize(OAuth2AuthorizeRequest request) {
        Authentication principal = request.getPrincipal() instanceof Authentication a ? a : null;
        if (principal == null) {
            return delegate.authorize(request);
        }
        // The gateway's token relay does not put the exchange on the request; Spring Security keeps it in the
        // Reactor context under the key ServerWebExchange.class (the same place the default manager looks).
        ServerWebExchange fromRequest = request.getAttribute(ServerWebExchange.class.getName());
        Mono<ServerWebExchange> exchanges = fromRequest != null ? Mono.just(fromRequest)
                : Mono.deferContextual(ctx -> Mono.justOrEmpty(ctx.<ServerWebExchange>getOrEmpty(ServerWebExchange.class)));
        return exchanges.flatMap(exchange -> authorize(request, principal, exchange))
                .switchIfEmpty(Mono.defer(() -> delegate.authorize(request)));
    }

    private Mono<OAuth2AuthorizedClient> authorize(OAuth2AuthorizeRequest request, Authentication principal,
            ServerWebExchange exchange) {
        return repository.<OAuth2AuthorizedClient>loadAuthorizedClient(request.getClientRegistrationId(), principal, exchange)
                .flatMap(current -> {
                    if (!needsRefresh(current)) {
                        return Mono.just(current);
                    }
                    return exchange.getSession().flatMap(session -> {
                        String key = session.getId() + "|" + request.getClientRegistrationId() + "|"
                                + current.getRefreshToken().getTokenValue();
                        Shared shared = refreshes.compute(key, (k, existing) -> existing != null ? existing
                                : new Shared(refresh(request, k), clock.instant()));
                        evictOld();
                        return shared.result()
                                // a request that read the old tokens from the session also stores the new ones
                                .flatMap(fresh -> repository.saveAuthorizedClient(fresh, principal, exchange).thenReturn(fresh));
                    });
                })
                .switchIfEmpty(Mono.defer(() -> delegate.authorize(request)));
    }

    private Mono<OAuth2AuthorizedClient> refresh(OAuth2AuthorizeRequest request, String key) {
        return delegate.authorize(request)
                .doOnError(e -> {
                    log.warn("Token refresh failed: {}", e.toString());
                    refreshes.remove(key);   // a failed refresh may be tried again
                })
                .cache();
    }

    private boolean needsRefresh(OAuth2AuthorizedClient client) {
        return client.getRefreshToken() != null && client.getAccessToken().getExpiresAt() != null
                && client.getAccessToken().getExpiresAt().minus(EXPIRY_SKEW).isBefore(clock.instant());
    }

    private void evictOld() {
        Instant cutoff = clock.instant().minus(GRACE);
        refreshes.entrySet().removeIf(e -> e.getValue().created().isBefore(cutoff));
    }
}
