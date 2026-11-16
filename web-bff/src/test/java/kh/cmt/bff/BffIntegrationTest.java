package kh.cmt.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * Step 1.5 "done when": the app calls an /api path with only the cookie.
 * Real BFF, real Redis session store; the auth server and cmt-api are replaced by {@link StubBackend}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bff.cookie.secure=true",
        "spring.security.oauth2.client.registration.cmt.client-secret=test-secret",
        "spring.security.oauth2.client.registration.cmt.redirect-uri=http://localhost:8081/login/oauth2/code/{registrationId}"
})
@AutoConfigureWebTestClient(timeout = "15000")
class BffIntegrationTest {

    static final StubBackend STUB = startStub();
    static final String AUTH_PUBLIC = "http://auth.example";
    static final ObjectMapper JSON = new ObjectMapper();

    static StubBackend startStub() {
        try {
            return new StubBackend();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bff.api-uri", STUB::url);
        registry.add("bff.auth-server.public-url", () -> AUTH_PUBLIC);
        registry.add("bff.auth-server.internal-url", STUB::url);
        registry.add("bff.post-logout-redirect-uri", () -> "http://localhost:8081/");
        registry.add("spring.security.oauth2.client.provider.cmt.authorization-uri", () -> AUTH_PUBLIC + "/oauth2/authorize");
        registry.add("spring.security.oauth2.client.provider.cmt.token-uri", () -> STUB.url() + "/oauth2/token");
        registry.add("spring.security.oauth2.client.provider.cmt.jwk-set-uri", () -> STUB.url() + "/oauth2/jwks");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");

    @Autowired WebTestClient web;
    @Autowired ReactiveSessionRepository<? extends Session> sessions;
    @LocalServerPort int port;

    @BeforeEach
    void resetStub() {
        STUB.tokenCalls.set(0);
        STUB.revokeCalls.clear();
        STUB.tokenRequestBodies.clear();
    }

    // ------------------------------------------------------------------ not signed in

    @Test
    void apiCallsWithoutASessionGetAPlain401NotARedirect() {
        web.get().uri("/api/v1/admin/me").exchange().expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.LOCATION);
        web.get().uri("/bff/user").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void browserPagesGoToTheLogin() {
        web.get().uri("/anything").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/oauth2/authorization/cmt");
    }

    @Test
    void theLoginRequestToTheAuthServerCarriesPkce() {
        URI location = web.get().uri("/oauth2/authorization/cmt").exchange()
                .expectStatus().isFound().returnResult(Void.class).getResponseHeaders().getLocation();
        assertThat(location.toString()).startsWith(AUTH_PUBLIC + "/oauth2/authorize?")
                .contains("response_type=code", "client_id=cmt-web-bff", "code_challenge=", "code_challenge_method=S256",
                        "redirect_uri=http://localhost:8081/login/oauth2/code/cmt", "state=");
        assertThat(location.getRawQuery()).contains("scope=openid%20profile");
    }

    @Test
    void theCsrfCookieIsHandedOutOnTheFirstResponse() {
        ResponseCookie xsrf = web.get().uri("/actuator/health").exchange().returnResult(Void.class)
                .getResponseCookies().getFirst("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();   // the script must be able to read it
    }

    // ------------------------------------------------------------------ signed in: only the cookie

    @Test
    void theAppCallsAnApiPathWithOnlyTheCookieAndCmtApiGetsTheBearerToken() throws Exception {
        ResponseCookie session = login(900);

        JsonNode echo = get("/api/v1/admin/me", session);

        assertThat(echo.get("path").asText()).isEqualTo("/api/v1/admin/me");
        assertThat(echo.get("authorization").asText()).isEqualTo("Bearer access-0");
        assertThat(echo.get("cookie").isNull()).as("the session cookie is not forwarded to cmt-api").isTrue();
    }

    @Test
    void theSessionCookieIsHttpOnlySecureAndSameSiteStrict() {
        ResponseCookie session = login(900);
        assertThat(session.getName()).isEqualTo("CMTSESSION");
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.isSecure()).isTrue();
        assertThat(session.getSameSite()).isEqualTo("Strict");
        assertThat(session.getPath()).isEqualTo("/");
    }

    @Test
    void changesNeedTheCsrfHeaderAndItIsNotForwarded() throws Exception {
        ResponseCookie session = login(900);
        ResponseCookie xsrf = csrfCookie(session);

        web.post().uri("/api/v1/admin/people").cookie(session.getName(), session.getValue())
                .exchange().expectStatus().isForbidden();                                   // no token at all
        web.post().uri("/api/v1/admin/people").cookie(session.getName(), session.getValue())
                .cookie("XSRF-TOKEN", xsrf.getValue()).header("X-XSRF-TOKEN", "wrong")
                .exchange().expectStatus().isForbidden();                                   // wrong token

        EntityExchangeResult<byte[]> ok = web.post().uri("/api/v1/admin/people")
                .cookie(session.getName(), session.getValue()).cookie("XSRF-TOKEN", xsrf.getValue())
                .header("X-XSRF-TOKEN", xsrf.getValue())
                .exchange().expectStatus().isOk().expectBody().returnResult();
        JsonNode echo = JSON.readTree(ok.getResponseBody());
        assertThat(echo.get("method").asText()).isEqualTo("POST");
        assertThat(echo.get("xsrf").isNull()).isTrue();
        assertThat(echo.get("authorization").asText()).isEqualTo("Bearer access-0");
    }

    @Test
    void theOwnPasswordChangeGoesToTheAuthServerWithTheBearerToken() throws Exception {
        web.get().uri("/bff/password/policy").exchange().expectStatus().isUnauthorized();   // signed out

        ResponseCookie session = login(900);
        ResponseCookie xsrf = csrfCookie(session);
        EntityExchangeResult<byte[]> ok = web.post().uri("/bff/password/change")
                .cookie(session.getName(), session.getValue()).cookie("XSRF-TOKEN", xsrf.getValue())
                .header("X-XSRF-TOKEN", xsrf.getValue())
                .exchange().expectStatus().isOk().expectBody().returnResult();
        JsonNode echo = JSON.readTree(ok.getResponseBody());
        assertThat(echo.get("path").asText()).isEqualTo("/auth/password/change");
        assertThat(echo.get("authorization").asText()).isEqualTo("Bearer access-0");
        assertThat(echo.get("cookie").isNull()).isTrue();
        assertThat(echo.get("xsrf").isNull()).isTrue();
        assertThat(get("/bff/password/policy", session).get("path").asText()).isEqualTo("/auth/password/policy");
    }

    @Test
    void userEndpointShowsWhoIsSignedInButNeverATokenAndTheIdTokenIsNotExposed() throws Exception {
        ResponseCookie session = login(900);
        String body = new String(web.get().uri("/bff/user").cookie(session.getName(), session.getValue())
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        JsonNode user = JSON.readTree(body);

        assertThat(user.get("sub").asText()).isEqualTo("account-1");
        assertThat(user.get("typ").asText()).isEqualTo("web");
        assertThat(user.get("role").asText()).isEqualTo("super_admin");
        assertThat(user.get("amr").get(1).asText()).isEqualTo("otp");
        assertThat(body).doesNotContain("access-0").doesNotContain("refresh-0").doesNotContain("id-token-value");
    }

    // ------------------------------------------------------------------ token refresh

    @Test
    void manyParallelCallsAfterTheTokenExpiredRefreshOnce() throws Exception {
        ResponseCookie session = login(-30);   // access token already expired

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(pool.submit(() -> get("/api/v1/admin/me", session)));
        }
        for (Future<JsonNode> call : calls) {
            assertThat(call.get().get("authorization").asText()).isEqualTo("Bearer new-access-1");
        }
        pool.shutdown();

        assertThat(STUB.tokenCalls).hasValue(1);
        assertThat(STUB.tokenRequestBodies.get(0)).contains("grant_type=refresh_token").contains("refresh_token=refresh-0");

        // and later calls use the stored new token without refreshing again
        assertThat(get("/api/v1/admin/me", session).get("authorization").asText()).isEqualTo("Bearer new-access-1");
        assertThat(STUB.tokenCalls).hasValue(1);
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutEndsTheSessionRevokesTheRefreshTokenAndSendsTheBrowserToTheAuthServerLogout() throws Exception {
        ResponseCookie session = login(900);
        ResponseCookie xsrf = csrfCookie(session);

        web.post().uri("/bff/logout").cookie(session.getName(), session.getValue()).exchange()
                .expectStatus().isForbidden();   // CSRF applies to logout too

        String body = new String(web.post().uri("/bff/logout").cookie(session.getName(), session.getValue())
                .cookie("XSRF-TOKEN", xsrf.getValue()).header("X-XSRF-TOKEN", xsrf.getValue())
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        String logoutUrl = JSON.readTree(body).get("logoutUrl").asText();

        assertThat(logoutUrl).startsWith(AUTH_PUBLIC + "/connect/logout?id_token_hint=id-token-value")
                .contains("post_logout_redirect_uri=http://localhost:8081/");
        assertThat(STUB.revokeCalls).hasSize(1);
        assertThat(STUB.revokeCalls.get(0)).startsWith("Basic ").contains("token=refresh-0").contains("token_type_hint=refresh_token");

        // the old cookie no longer works
        web.get().uri("/bff/user").cookie(session.getName(), session.getValue()).exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/v1/admin/me").cookie(session.getName(), session.getValue()).exchange().expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------ login rules of the role (step 3.5)

    @Test
    void theSessionIdleTimeoutIsTheRoles() throws Exception {
        ResponseCookie session = loginWithRules(Map.of("sess_min", 45));
        web.get().uri("/bff/user").cookie(session.getName(), session.getValue()).exchange().expectStatus().isOk();

        Session stored = sessions.findById(session.getValue()).block();
        assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void outsideTheAllowedHoursTheSessionEnds() throws Exception {
        ResponseCookie session = loginWithRules(Map.of("login_from", hoursFromNow(1), "login_to", hoursFromNow(2)));

        String body = new String(web.get().uri("/api/v1/admin/me").cookie(session.getName(), session.getValue()).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectBody().returnResult().getResponseBody());
        JsonNode problem = JSON.readTree(body);
        assertThat(problem.get("code").asText()).isEqualTo("LOGIN_NOT_ALLOWED");
        assertThat(problem.get("reason").asText()).isEqualTo("OUTSIDE_HOURS");

        // the session is gone, not just this one request refused
        web.get().uri("/bff/user").cookie(session.getName(), session.getValue()).exchange().expectStatus().isUnauthorized();
        assertThat(STUB.tokenCalls).hasValue(0);
    }

    @Test
    void aBrowserPageOutsideTheRulesGoesToTheAuthServersNotAllowedPage() {
        int today = LocalDate.now(ZONE).getDayOfWeek().getValue();
        ResponseCookie session = loginWithRules(Map.of("login_days", List.of(today == 7 ? 1 : today + 1)));
        web.get().uri("/anything").cookie(session.getName(), session.getValue()).exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, AUTH_PUBLIC + "/login/denied?reason=DAY_NOT_ALLOWED");
    }

    @Test
    void onlyFromTheAllowedAddresses() throws Exception {
        ResponseCookie elsewhere = loginWithRules(Map.of("login_ips", List.of("10.9.0.0/16")));
        web.get().uri("/api/v1/admin/me").cookie(elsewhere.getName(), elsewhere.getValue()).exchange()
                .expectStatus().isUnauthorized().expectBody().jsonPath("$.reason").isEqualTo("IP_NOT_ALLOWED");

        // over a real connection from this machine (the default test client has no remote address, which is refused)
        ResponseCookie here = loginWithRules(Map.of("login_ips", List.of("127.0.0.1", "::1"),
                "login_from", hoursFromNow(-1), "login_to", hoursFromNow(1)));
        WebTestClient overTheNetwork = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        EntityExchangeResult<byte[]> allowed = overTheNetwork.get().uri("/api/v1/admin/me").cookie(here.getName(), here.getValue())
                .exchange().expectBody().returnResult();
        String body = new String(allowed.getResponseBody() == null ? new byte[0] : allowed.getResponseBody());
        assertThat(allowed.getStatus().value()).as(body).isEqualTo(200);
        assertThat(JSON.readTree(body).get("authorization").asText()).startsWith("Bearer ey");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode get(String path, ResponseCookie session) throws IOException {
        byte[] body = web.get().uri(path).cookie(session.getName(), session.getValue())
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return JSON.readTree(body);
    }

    private ResponseCookie csrfCookie(ResponseCookie session) {
        return web.get().uri("/bff/user").cookie(session.getName(), session.getValue())
                .exchange().returnResult(Void.class).getResponseCookies().getFirst("XSRF-TOKEN");
    }

    /** Signs in through the test-only door and returns the session cookie the browser would get. */
    private ResponseCookie login(long accessTokenSeconds) {
        return login(accessTokenSeconds, "access-0");
    }

    private ResponseCookie login(long accessTokenSeconds, String accessToken) {
        return web.post().uri(uri -> uri.path("/test/login").queryParam("accessSeconds", accessTokenSeconds)
                        .queryParam("accessToken", accessToken).build())
                .exchange().expectStatus().isOk().returnResult(Void.class).getResponseCookies().getFirst("CMTSESSION");
    }

    /** Signs in with an access token that carries these login-rule claims, as the auth server issues them. */
    private ResponseCookie loginWithRules(Map<String, Object> claims) {
        JWTClaimsSet.Builder token = new JWTClaimsSet.Builder().subject("account-1").claim("typ", "web")
                .claim("login_tz", ZONE.getId());
        claims.forEach(token::claim);
        return login(900, new PlainJWT(token.build()).serialize());
    }

    /** "HH:mm" so many hours from now, in school time. */
    private static String hoursFromNow(int hours) {
        return LocalTime.now(ZONE).plusHours(hours).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /**
     * Test-only: puts a signed-in user (OIDC login + tokens) into the session, as the real login would after
     * the auth server redirected back. Lives in a separate security chain so the real chain is not loosened.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestLoginConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityWebFilterChain testLoginChain(ServerHttpSecurity http) {
            return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/test/**"))
                    .authorizeExchange(a -> a.anyExchange().permitAll())
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .build();
        }

        @Bean
        TestLoginController testLoginController(ReactiveClientRegistrationRepository registrations,
                ServerOAuth2AuthorizedClientRepository authorizedClients) {
            return new TestLoginController(registrations, authorizedClients);
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        }
    }

    @RestController
    static class TestLoginController {
        private final ReactiveClientRegistrationRepository registrations;
        private final ServerOAuth2AuthorizedClientRepository authorizedClients;
        private final WebSessionServerSecurityContextRepository contexts = new WebSessionServerSecurityContextRepository();

        TestLoginController(ReactiveClientRegistrationRepository registrations,
                ServerOAuth2AuthorizedClientRepository authorizedClients) {
            this.registrations = registrations;
            this.authorizedClients = authorizedClients;
        }

        @PostMapping("/test/login")
        Mono<Void> login(@RequestParam long accessSeconds, @RequestParam(defaultValue = "access-0") String accessToken,
                ServerWebExchange exchange) {
            Instant now = Instant.now();
            OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value").subject("account-1")
                    .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(15)))
                    .claim("typ", "web").claim("role", "super_admin").claim("perm_ver", 1)
                    .claim("school_id", "00000000-0000-0000-0000-000000000001").claim("locale", "km")
                    .claim("amr", List.of("pwd", "otp")).build();
            var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
            DefaultOidcUser user = new DefaultOidcUser(authorities, idToken, "sub");
            OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(user, authorities, "cmt");

            return registrations.findByRegistrationId("cmt").flatMap(registration -> {
                OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, authentication.getName(),
                        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken,
                                now.minusSeconds(900), now.plusSeconds(accessSeconds)),
                        new OAuth2RefreshToken("refresh-0", now.minusSeconds(900)));
                return authorizedClients.saveAuthorizedClient(client, authentication, exchange)
                        .then(contexts.save(exchange, new SecurityContextImpl(authentication)));
            });
        }
    }
}
