package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.support.TestcontainersConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 1.6 "done when": an app token gets 403 on /admin/**.
 * Tokens are signed with a test key; the API fetches the matching public key from a local JWKS endpoint,
 * so the whole validation path (signature, expiry, issuer, type) is the real one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, JwtSecurityTest.TestEndpoints.class})
class JwtSecurityTest {

    static final String ISSUER = "http://auth.test";
    static final UUID SCHOOL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static final RSAKey KEY = newKey("test-key-1");
    static final RSAKey OTHER_KEY = newKey("test-key-1");   // same kid, different key: a forged signature
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

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mvc;
    @Autowired SchoolContext schoolContext;

    // ------------------------------------------------------------------ route groups

    @Test
    void anAppTokenIsRefusedOnAdminRoutesButWorksOnAppRoutes() throws Exception {
        String token = token(claims("app", "staff").claim("person_id", "11111111-1111-1111-1111-111111111111")
                .claim("device_id", "22222222-2222-2222-2222-222222222222"));

        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));

        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("app"))
                .andExpect(jsonPath("$.role").value("staff"))
                .andExpect(jsonPath("$.personId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.deviceId").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.schoolId").value(SCHOOL.toString()))
                .andExpect(jsonPath("$.permVersion").value(3))
                .andExpect(jsonPath("$.locale").value("km"))
                .andExpect(jsonPath("$.amr[0]").value("pwd"))
                .andExpect(jsonPath("$.permissions").doesNotExist());
    }

    @Test
    void aWebTokenWorksOnAdminRoutesButNotOnAppRoutes() throws Exception {
        String token = token(claims("web", "super_admin").claim("amr", List.of("pwd", "otp")));

        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("web"))
                .andExpect(jsonPath("$.role").value("super_admin"))
                .andExpect(jsonPath("$.amr[1]").value("otp"))
                // the Admin Web builds its menu from these
                .andExpect(jsonPath("$.permissions.people").value(org.hamcrest.Matchers.hasItems("view", "create", "edit")))
                .andExpect(jsonPath("$.permissions.dashboard").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("edit"))));

        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void aJobTokenCannotUseEitherGroup() throws Exception {
        String token = token(claims("job", null));
        mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void approvalsAreOpenToOfficeUsersAndAppAdminsOnly() throws Exception {
        String web = token(claims("web", "hr"));
        String appAdmin = token(claims("app", "admin"));
        String appStaff = token(claims("app", "staff"));
        String appTeacher = token(claims("app", "teacher"));

        mvc.perform(get("/api/v1/admin/approvals/ping").header("Authorization", "Bearer " + web)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/approvals/ping").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/approvals/ping").header("Authorization", "Bearer " + appStaff)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/approvals/ping").header("Authorization", "Bearer " + appTeacher)).andExpect(status().isForbidden());

        // an app admin gets nothing else under /admin
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isForbidden());
    }

    @Test
    void theAdminAppReadsPeopleAndAttendanceButChangesNothing() throws Exception {
        String appAdmin = token(claims("app", "admin"));
        String appStaff = token(claims("app", "staff"));
        String someone = "/api/v1/admin/people/" + UUID.randomUUID();

        // the Staff tab and School today
        mvc.perform(get("/api/v1/admin/people").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/attendance/day").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/attendance/month").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isOk());
        mvc.perform(get(someone).header("Authorization", "Bearer " + appAdmin)).andExpect(status().isNotFound());

        // staff never; and the admin app gets nothing more than reading
        mvc.perform(get("/api/v1/admin/people").header("Authorization", "Bearer " + appStaff)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/attendance/day").header("Authorization", "Bearer " + appStaff)).andExpect(status().isForbidden());
        mvc.perform(get(someone + "/pay").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isForbidden());
        mvc.perform(get(someone + "/documents").header("Authorization", "Bearer " + appAdmin)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(someone)
                .header("Authorization", "Bearer " + appAdmin).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/attendance/export.xlsx").header("Authorization", "Bearer " + appAdmin))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPathsAreDeniedEvenWithAValidToken() throws Exception {
        String token = token(claims("web", "super_admin"));
        mvc.perform(get("/api/v1/something/else").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/secret").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void healthAndApiDocsStayPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ bad tokens

    @Test
    void noTokenIs401WithAProblemBody() throws Exception {
        mvc.perform(get("/api/v1/app/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.type").value("urn:cmt:error:UNAUTHORIZED"));
    }

    @Test
    void expiredWrongIssuerForgedAndUntypedTokensAreAll401() throws Exception {
        String expired = token(claims("app", "staff").expirationTime(Date.from(Instant.now().minusSeconds(120))));
        String wrongIssuer = token(claims("app", "staff").issuer("http://evil.example"));
        String forged = token(claims("app", "staff"), OTHER_KEY);
        String noType = token(new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer(ISSUER)
                .expirationTime(Date.from(Instant.now().plusSeconds(600))));
        String unknownType = token(claims("martian", "staff"));

        for (String bad : List.of(expired, wrongIssuer, forged, noType, unknownType, "not-a-jwt")) {
            mvc.perform(get("/api/v1/app/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void aTokenWithoutTheBearerPrefixIsRefused() throws Exception {
        mvc.perform(get("/api/v1/app/me").header("Authorization", token(claims("app", "staff"))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ CurrentUser and tenant

    @Test
    void theSchoolComesFromTheTokenWhenThereIsACaller_andFromConfigurationWhenThereIsNot() throws Exception {
        assertThat(schoolContext.currentSchoolId()).isEqualTo(SCHOOL);   // no caller: configured school (jobs, start-up)

        UUID other = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("x").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("typ", "web").claim("school_id", other.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(schoolContext.currentSchoolId()).isEqualTo(other);
        assertThat(CurrentUser.require().schoolId()).isEqualTo(other);
    }

    @Test
    void requireFailsWithoutACaller() {
        org.junit.jupiter.api.Assertions.assertThrows(kh.cmt.shared.web.ApiException.class, CurrentUser::require);
    }

    // ------------------------------------------------------------------ helpers

    private static JWTClaimsSet.Builder claims(String type, String role) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString()).issuer(ISSUER)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("typ", type).claim("school_id", SCHOOL.toString())
                .claim("perm_ver", 3).claim("locale", "km").claim("amr", List.of("pwd"));
        if (role != null) {
            claims.claim("role", role);
        }
        return claims;
    }

    private static String token(JWTClaimsSet.Builder claims) throws Exception {
        return token(claims, KEY);
    }

    private static String token(JWTClaimsSet.Builder claims, RSAKey signWith) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key-1").build(), claims.build());
        jwt.sign(new RSASSASigner(signWith));
        return jwt.serialize();
    }

    private static RSAKey newKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
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

    /** Stands in for the future approvals endpoints, which are open to office users and app admins. */
    @RestController
    static class TestEndpoints {
        @GetMapping("/api/v1/admin/approvals/ping")
        String ping() {
            return "pong";
        }
    }
}
