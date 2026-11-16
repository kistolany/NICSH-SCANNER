package kh.cmt.auth.config;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kh.cmt.auth.AuthProperties;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.keys.DatabaseJwkSource;
import kh.cmt.auth.login.LoginResponses;
import kh.cmt.auth.login.LoginRuleService;
import kh.cmt.auth.login.LoginRulesFilter;
import kh.cmt.auth.login.MustChangePasswordFilter;
import kh.cmt.auth.login.WebLoginPages;
import kh.cmt.auth.twofactor.SecondFactorFilter;
import kh.cmt.auth.twofactor.TwoFactorService;
import kh.cmt.auth.keys.SigningKeyService;
import kh.cmt.auth.token.RefreshTokenFamilyAuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class AuthorizationServerConfig {

    /** OAuth2 / OIDC endpoints: /oauth2/authorize, /oauth2/token, /oauth2/jwks, /.well-known/... */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, RegisteredClientRepository clients,
            AccountRepository accounts, TwoFactorService twoFactor, LoginRuleService loginRules, WebLoginPages pages)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        .oidc(Customizer.withDefaults())
                        // Public clients get and can use refresh tokens (see PkcePublicClientAuthenticationProvider)
                        .clientAuthentication(auth -> auth
                                .authenticationConverter(new PublicClientRefreshConverter())
                                .authenticationProviders(providers -> {
                                    int i = indexOfSpringPublicClientProvider(providers);
                                    providers.set(i, new PkcePublicClientAuthenticationProvider(providers.get(i), clients));
                                })))
                // right after the session is loaded: the authorize endpoint filter sits early in the chain.
                // Login rules first: a refused user is not asked to change the password or enter a code.
                .addFilterAfter(new LoginRulesFilter(accounts, loginRules, pages), SecurityContextHolderFilter.class)
                .addFilterAfter(new MustChangePasswordFilter(accounts, pages), LoginRulesFilter.class)
                .addFilterAfter(new SecondFactorFilter(accounts, twoFactor, pages), MustChangePasswordFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // the sign-in screen: the admin web's own (auth.web.login-url) or this server's /login
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(pages.login()),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    private static int indexOfSpringPublicClientProvider(List<AuthenticationProvider> providers) {
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getClass().getSimpleName().equals("PublicClientAuthenticationProvider")) {
                return i;
            }
        }
        throw new IllegalStateException("Spring's PublicClientAuthenticationProvider not found; "
                + "the Authorization Server version changed - review PkcePublicClientAuthenticationProvider");
    }

    /**
     * /auth/**: the app's login and refresh are public (rate limited, step 1.7); password change and app logout need a
     * bearer access token of this server. (No forgot / reset by email or SMS: an admin gives new passwords.)
     */
    @Bean
    @Order(2)
    SecurityFilterChain passwordApiChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/auth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/auth/refresh").permitAll()
                        // the refused phone has no login: its X-Device-Change-Token is checked by the controller
                        .requestMatchers("/auth/device/change-request").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Everything else: the password login ({@code POST /login}), the admin web screens' JSON ({@code /login/api/**})
     * and this server's own plain pages (login, change password, 2-step, "sign-in not allowed").
     */
    @Bean
    @Order(3)
    SecurityFilterChain loginChain(HttpSecurity http, WebLoginPages pages) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error", "/login/denied",
                                "/login/api/state", "/login/api/cancel").permitAll()
                        .requestMatchers(HttpMethod.GET, "/login").permitAll()
                        .anyRequest().authenticated())
                // the JSON calls answer 401 instead of a redirect when the login session is gone
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PathPatternRequestMatcher.withDefaults().matcher("/login/api/**")));
        if (pages.external()) {
            http
                    .cors(cors -> cors.configurationSource(webLoginCors(pages)))
                    .formLogin(form -> form
                            .loginPage(pages.login())
                            .loginProcessingUrl("/login")
                            .successHandler(LoginResponses.success(pages))
                            .failureHandler(LoginResponses.failure(pages)));
        } else {
            http.formLogin(form -> form
                    .successHandler(LoginResponses.success(pages))
                    .failureHandler(LoginResponses.failure(pages)));
        }
        return http.build();
    }

    /** The admin web (other port / sub-domain) calls /login and /login/api/** with this server's session cookie. */
    private static CorsConfigurationSource webLoginCors(WebLoginPages pages) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(pages.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Accept", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/login", cors);
        source.registerCorsConfiguration("/login/api/**", cors);
        return source;
    }

    @Bean
    Clock clock(@Value("${cmt.timezone:Asia/Phnom_Penh}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }

    /**
     * New hashes are Argon2id (OWASP minimum: 19 MiB, 2 iterations, 1 lane), stored as {@code {argon2}...}.
     * Older {@code {bcrypt}...} hashes still verify and are re-hashed to Argon2id at the next good login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", new Argon2PasswordEncoder(16, 32, 1, 19456, 2),
                "bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("argon2", encoders);
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate, RegisteredClientRepository clients,
            JdbcClient jdbcClient, Clock clock) {
        return new RefreshTokenFamilyAuthorizationService(
                new JdbcOAuth2AuthorizationService(jdbcTemplate, clients), jdbcClient, clock);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
            RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(SigningKeyService keys) {
        return new DatabaseJwkSource(keys);
    }

    /**
     * Signs with the active key only. (The shared JWK source also holds retired public keys, which the
     * encoder would otherwise refuse to choose between; the JWKS endpoint still publishes all of them.)
     */
    @Bean
    JwtEncoder jwtEncoder(SigningKeyService keys) {
        JWKSource<SecurityContext> signingKeys = (selector, context) ->
                selector.select(new JWKSet(keys.keys().stream().filter(JWK::isPrivate).toList()));
        return new NimbusJwtEncoder(signingKeys);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.issuer()).build();
    }
}
