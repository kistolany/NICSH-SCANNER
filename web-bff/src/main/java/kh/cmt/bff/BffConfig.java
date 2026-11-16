package kh.cmt.bff;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

@Configuration
class BffConfig {

    /**
     * {@code /api/**} goes to cmt-api and {@code /bff/password/**} to the auth server's {@code /auth/password/**}
     * (the signed-in user's own password change), both with the user's access token; the cookie and CSRF header stay
     * behind.
     */
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, BffProperties props) {
        return builder.routes()
                .route("cmt-api", r -> r.path("/api/**")
                        .filters(f -> f
                                .tokenRelay("cmt")
                                .removeRequestHeader("Cookie")
                                .removeRequestHeader("X-XSRF-TOKEN"))
                        .uri(props.apiUri()))
                .route("own-password", r -> r.path("/bff/password/**")
                        .filters(f -> f
                                .rewritePath("/bff/password/(?<rest>.*)", "/auth/password/${rest}")
                                .tokenRelay("cmt")
                                .removeRequestHeader("Cookie")
                                .removeRequestHeader("X-XSRF-TOKEN"))
                        .uri(props.authServer().internalUrl()))
                .build();
    }

    /**
     * The user's tokens live in the browser session (Redis), next to the sign-in itself. The default keeps them in
     * this process's memory: after a BFF restart the session was still signed in but had no token, and every
     * {@code /api} call was redirected to the auth server ("Failed to fetch" in the admin web).
     */
    @Bean
    ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }

    /** Refreshes the access token when it has expired, once per session (see the class for why). */
    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(ReactiveClientRegistrationRepository registrations,
            ServerOAuth2AuthorizedClientRepository authorizedClients, Clock clock) {
        DefaultReactiveOAuth2AuthorizedClientManager manager =
                new DefaultReactiveOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return new SingleFlightAuthorizedClientManager(manager, authorizedClients, clock);
    }

    /** The session cookie: HttpOnly, SameSite=Strict, Secure outside local development. */
    @Bean
    WebSessionIdResolver webSessionIdResolver(BffProperties props) {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName(props.cookie().name());
        resolver.addCookieInitializer((ResponseCookie.ResponseCookieBuilder cookie) -> cookie
                .path("/")
                .httpOnly(true)
                .secure(props.cookie().secure())
                .sameSite("Strict"));
        return resolver;
    }

    @Bean
    Clock clock(@Value("${bff.timezone:Asia/Phnom_Penh}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }

    @Bean
    WebClient authServerClient() {
        return WebClient.builder().build();
    }
}
