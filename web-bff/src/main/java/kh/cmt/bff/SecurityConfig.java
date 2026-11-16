package kh.cmt.bff;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint.DelegateEntry;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
class SecurityConfig {

    static final String REGISTRATION = "cmt";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            ReactiveClientRegistrationRepository registrations, ServerOAuth2AuthorizedClientRepository authorizedClients,
            Clock clock, BffProperties props) {

        // The auth server requires PKCE for this client, so every login request carries a code challenge.
        DefaultServerOAuth2AuthorizationRequestResolver authorizationRequests =
                new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        return http
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyExchange().authenticated())
                .oauth2Login(login -> login.authorizationRequestResolver(authorizationRequests))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint()))
                // Single-page app CSRF: token in a cookie the script can read, sent back in X-XSRF-TOKEN.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieWebFilter(), SecurityWebFiltersOrder.CSRF)
                // signed-in users only: the role's allowed days / hours / addresses and its session timeout
                .addFilterAfter(new LoginRulesWebFilter(authorizedClients, clock, props.authServer().publicUrl()),
                        SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }

    /** The app's API calls get a plain 401 (the script decides what to do); browser pages go to the login. */
    private static DelegatingServerAuthenticationEntryPoint entryPoint() {
        DelegatingServerAuthenticationEntryPoint entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegateEntry(ServerWebExchangeMatchers.pathMatchers("/api/**", "/bff/**"),
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));
        entryPoint.setDefaultEntryPoint(new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/" + REGISTRATION));
        return entryPoint;
    }
}
