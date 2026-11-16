package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import kh.cmt.support.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Step 1.7 "done when" (cmt-api side): the API answers 429 after the limit.
 * The real limits are 300 per minute per user and 20 per minute per user for scans; here they are 5 and 3.
 */
@SpringBootTest(properties = {"cmt.rate-limit.general.capacity=5", "cmt.rate-limit.scan.capacity=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RateLimitTest {

    static final String ISSUER = "http://auth.test";
    static final RSAKey KEY = newKey();
    static final HttpServer JWKS = startJwksServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("cmt.security.issuer", () -> ISSUER);
        registry.add("cmt.security.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/oauth2/jwks");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @Autowired MockMvc mvc;

    @Test
    void theApiAnswers429AfterTheGeneralLimitPerUser() throws Exception {
        String alice = token();
        for (int i = 1; i <= 5; i++) {
            mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + alice)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + alice))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        // another user, from the very same IP, is not affected
        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + token())).andExpect(status().isOk());
    }

    @Test
    void scanHasItsOwnStricterLimit() throws Exception {
        String bob = token();
        for (int i = 1; i <= 3; i++) {
            // the real scan endpoint: an empty scan is refused (400), but it still counts
            int answer = mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", "Bearer " + bob))
                    .andReturn().getResponse().getStatus();
            assertThat(answer).isNotEqualTo(429);
        }
        mvc.perform(post("/api/v1/app/attendance/scan").header("Authorization", "Bearer " + bob))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // bob can still use the rest of the API (general limit not used up: 4 of 5 so far)
        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + bob)).andExpect(status().isOk());
    }

    @Test
    void callersWithoutATokenAreCountedPerIp() throws Exception {
        RequestPostProcessor fromIp = request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        };
        for (int i = 1; i <= 5; i++) {
            mvc.perform(get("/api/v1/app/me").with(fromIp)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/app/me").with(fromIp)).andExpect(status().isTooManyRequests());
        // a different IP is still answered normally
        mvc.perform(get("/api/v1/app/me").with(r -> {
            r.setRemoteAddr("203.0.113.8");
            return r;
        })).andExpect(status().isUnauthorized());
    }

    @Test
    void healthChecksAreNeverLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A token for a brand-new user (each user has its own bucket). */
    private static String token() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer(ISSUER)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("typ", "app").claim("role", "staff")
                .claim("school_id", "00000000-0000-0000-0000-000000000001").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rl-key").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("rl-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/oauth2/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
