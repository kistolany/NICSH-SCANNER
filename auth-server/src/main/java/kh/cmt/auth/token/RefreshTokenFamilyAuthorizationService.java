package kh.cmt.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

/**
 * Refresh-token families and reuse detection, on top of Spring's authorization store.
 * <p>
 * A family is one login session (= the OAuth2 authorization id). Every refresh token that gets issued is
 * recorded in {@code refresh_token} as a hash, pointing at the token it replaced ({@code rotated_from});
 * the replaced one is marked used. If a token that was already used is presented again, somebody holds a
 * stolen copy: the whole family is revoked and the session is removed, so even the newest token stops working.
 */
public class RefreshTokenFamilyAuthorizationService implements OAuth2AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenFamilyAuthorizationService.class);

    private final OAuth2AuthorizationService delegate;
    private final JdbcClient jdbc;
    private final Clock clock;

    public RefreshTokenFamilyAuthorizationService(OAuth2AuthorizationService delegate, JdbcClient jdbc, Clock clock) {
        this.delegate = delegate;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        if (refresh != null) {
            record(authorization, refresh.getToken());
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
        revokeFamily(UUID.fromString(authorization.getId()));
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        OAuth2Authorization authorization = delegate.findByToken(token, tokenType);
        if (tokenType != null && !OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            return authorization;
        }
        Optional<Row> row = find(token);
        if (row.isEmpty()) {
            return authorization;   // not one of ours (or not a refresh token)
        }
        if (row.get().revokedAt() != null) {
            return null;
        }
        if (row.get().usedAt() != null) {
            log.warn("Refresh token reuse detected; revoking session {}", row.get().familyId());
            OAuth2Authorization session = delegate.findById(row.get().familyId().toString());
            if (session != null) {
                remove(session);
            } else {
                revokeFamily(row.get().familyId());
            }
            return null;
        }
        return authorization;
    }

    // ------------------------------------------------------------------ bookkeeping

    private record Row(UUID id, UUID familyId, Instant usedAt, Instant revokedAt) {
    }

    private Optional<Row> find(String tokenValue) {
        return jdbc.sql("select id, family_id, used_at, revoked_at from refresh_token where token_hash = :hash")
                .param("hash", hash(tokenValue))
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        instant(rs.getTimestamp("used_at")),
                        instant(rs.getTimestamp("revoked_at"))))
                .optional();
    }

    private void record(OAuth2Authorization authorization, OAuth2RefreshToken token) {
        String hash = hash(token.getTokenValue());
        boolean known = jdbc.sql("select count(*) from refresh_token where token_hash = :hash")
                .param("hash", hash).query(Integer.class).single() > 0;
        if (known) {
            return;
        }
        Optional<UUID> accountId = jdbc.sql("select id from account where lower(username) = lower(:u)")
                .param("u", authorization.getPrincipalName()).query(UUID.class).optional();
        if (accountId.isEmpty()) {
            return;
        }
        UUID family = UUID.fromString(authorization.getId());
        Optional<UUID> previous = jdbc.sql("""
                select id from refresh_token
                where family_id = :family and used_at is null and revoked_at is null
                order by issued_at desc limit 1
                """).param("family", family).query(UUID.class).optional();
        var now = clock.instant().atOffset(java.time.ZoneOffset.UTC);
        previous.ifPresent(prev -> jdbc.sql("update refresh_token set used_at = :now where id = :id")
                .param("now", now).param("id", prev).update());

        Instant issued = token.getIssuedAt() != null ? token.getIssuedAt() : clock.instant();
        Instant expires = token.getExpiresAt() != null ? token.getExpiresAt() : issued;
        jdbc.sql("""
                insert into refresh_token (id, account_id, family_id, token_hash, rotated_from, issued_at, expires_at)
                values (:id, :account, :family, :hash, :prev, :issued, :expires)
                """)
                .param("id", UUID.randomUUID())
                .param("account", accountId.get())
                .param("family", family)
                .param("hash", hash)
                .param("prev", previous.orElse(null))
                .param("issued", issued.atOffset(java.time.ZoneOffset.UTC))
                .param("expires", expires.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private void revokeFamily(UUID familyId) {
        jdbc.sql("update refresh_token set revoked_at = :now where family_id = :family and revoked_at is null")
                .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .param("family", familyId).update();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    static String hash(String tokenValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tokenValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
