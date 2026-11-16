package kh.cmt.auth.keys;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RS256 signing keys with rotation. One key is "active" and signs new tokens. After {@link #rotate()} the
 * old key is "retired": it no longer signs, but its public half stays in the JWKS so tokens signed before
 * the rotation still verify. Remove retired keys with {@link #removeRetiredBefore} once those tokens expired.
 * Private keys are stored AES-GCM encrypted.
 */
@Service
public class SigningKeyService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecretCipher cipher;
    private volatile Cached cached;

    private record Cached(List<JWK> keys, Instant loadedAt) {
    }

    private record KeyRow(String kid, String publicKey, String privateKeyEnc, String status) {
    }

    public SigningKeyService(JdbcClient jdbc, Clock clock, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cipher = cipher;
    }

    /** Makes sure one active key exists (first start). Safe to call from several instances at once. */
    public void ensureActiveKey() {
        Integer active = jdbc.sql("select count(*) from signing_key where status = 'active'")
                .query(Integer.class).single();
        if (active == 0) {
            try {
                insertNewActiveKey();
            } catch (DuplicateKeyException anotherInstanceWon) {
                // fine: exactly one active key exists
            }
        }
        cached = null;
    }

    /** Retires the active key and creates a new active one. */
    @Transactional
    public String rotate() {
        jdbc.sql("update signing_key set status = 'retired', retired_at = :now where status = 'active'")
                .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC)).update();
        String kid = insertNewActiveKey();
        cached = null;
        return kid;
    }

    /** Deletes retired keys retired before the given instant. Returns how many were removed. */
    public int removeRetiredBefore(Instant cutoff) {
        int removed = jdbc.sql("delete from signing_key where status = 'retired' and retired_at < :cutoff")
                .param("cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC)).update();
        cached = null;
        return removed;
    }

    /**
     * All keys for the JWK source: the active key with its private part (used to sign) and retired keys
     * as public-only keys (used only to publish/verify).
     */
    public List<JWK> keys() {
        Cached c = cached;
        Instant now = clock.instant();
        if (c == null || c.loadedAt().plus(CACHE_TTL).isBefore(now)) {
            c = new Cached(load(), now);
            cached = c;
        }
        return c.keys();
    }

    private List<JWK> load() {
        return jdbc.sql("select kid, public_key, private_key_enc, status from signing_key order by created_at desc")
                .query((rs, n) -> new KeyRow(rs.getString("kid"), rs.getString("public_key"),
                        rs.getString("private_key_enc"), rs.getString("status")))
                .list().stream()
                .map(this::toJwk)
                .toList();
    }

    private JWK toJwk(KeyRow row) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(row.publicKey())));
            RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                    .keyID(row.kid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256);
            if ("active".equals(row.status())) {
                RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(cipher.decrypt(row.privateKeyEnc())));
                builder.privateKey(privateKey);
            }
            return builder.build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load signing key " + row.kid(), e);
        }
    }

    private String insertNewActiveKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String kid = UUID.randomUUID().toString();
            jdbc.sql("""
                    insert into signing_key (kid, algorithm, public_key, private_key_enc, status, created_at)
                    values (:kid, 'RS256', :pub, :priv, 'active', :now)
                    """)
                    .param("kid", kid)
                    .param("pub", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .param("priv", cipher.encrypt(pair.getPrivate().getEncoded()))
                    .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                    .update();
            return kid;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate signing key", e);
        }
    }
}
