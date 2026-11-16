package kh.cmt.support;

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
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Access tokens for tests. Signed with a throw-away key; the API fetches the matching public key from a local
 * JWKS endpoint exactly as it would from the auth server, so the real validation path is exercised.
 * Use through {@link ApiTest}.
 */
public final class TestJwt {

    public static final String ISSUER = "http://auth.test";
    public static final UUID SCHOOL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final RSAKey KEY = newKey();
    private static final HttpServer JWKS = startJwksServer();

    private TestJwt() {
    }

    /** A signed-in caller: its account id ({@code sub}) and the Authorization header value. */
    public record Caller(UUID userId, String bearer) {
    }

    public static Caller webUser(String role) {
        return caller("web", role, SCHOOL);
    }

    /** A signed-in office user of another school. */
    public static Caller webUser(String role, UUID schoolId) {
        return caller("web", role, schoolId);
    }

    /** An office user signed in with this account ({@code sub}), e.g. the account of a web user made in the test. */
    public static Caller webUser(String role, UUID schoolId, UUID accountId) {
        return caller("web", role, schoolId, accountId);
    }

    public static Caller appUser(String role) {
        return caller("app", role, SCHOOL);
    }

    /**
     * A caller whose token carries {@code person_id} (an app user, or a web user linked to a person), signed in with
     * account {@code accountId}.
     */
    public static Caller withPerson(String type, String role, UUID schoolId, UUID accountId, UUID personId) {
        return caller(type, role, schoolId, accountId, personId);
    }

    /** An office user whose token carries {@code web_user_id}, as the auth server issues it for a web user. */
    public static Caller ofWebUser(String role, UUID accountId, UUID webUserId) {
        return caller("web", role, SCHOOL, accountId, null, webUserId);
    }

    /** An app user signed in on phone {@code deviceId} (claim {@code device_id}), as the app login issues it. */
    public static Caller withDevice(String role, UUID schoolId, UUID accountId, UUID personId, UUID deviceId) {
        return caller("app", role, schoolId, accountId, personId, null, deviceId);
    }

    private static Caller caller(String type, String role, UUID schoolId) {
        return caller(type, role, schoolId, UUID.randomUUID());
    }

    private static Caller caller(String type, String role, UUID schoolId, UUID userId) {
        return caller(type, role, schoolId, userId, null);
    }

    private static Caller caller(String type, String role, UUID schoolId, UUID userId, UUID personId) {
        return caller(type, role, schoolId, userId, personId, null);
    }

    private static Caller caller(String type, String role, UUID schoolId, UUID userId, UUID personId, UUID webUserId) {
        return caller(type, role, schoolId, userId, personId, webUserId, null);
    }

    private static Caller caller(String type, String role, UUID schoolId, UUID userId, UUID personId, UUID webUserId, UUID deviceId) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().subject(userId.toString()).issuer(ISSUER)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("typ", type).claim("role", role).claim("school_id", schoolId.toString())
                .claim("perm_ver", 1).claim("locale", "km").claim("amr", List.of("pwd"));
        if (personId != null) {
            builder.claim("person_id", personId.toString());
        }
        if (webUserId != null) {
            builder.claim("web_user_id", webUserId.toString());
        }
        if (deviceId != null) {
            builder.claim("device_id", deviceId.toString());
        }
        JWTClaimsSet claims = builder.build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return new Caller(userId, "Bearer " + jwt.serialize());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Points the API at the local JWKS endpoint. */
    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "cmt.security.issuer=" + ISSUER,
                    "cmt.security.jwk-set-uri=http://127.0.0.1:" + JWKS.getAddress().getPort() + "/oauth2/jwks")
                    .applyTo(context);
        }
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
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
