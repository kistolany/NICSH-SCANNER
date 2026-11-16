package kh.cmt.auth.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The phone of an app account (V9): the school's device rules, blocked phones, the token a refused phone gets to ask
 * for a change, and the device history. Plain SQL on the auth schema; cmt-api's devices module works on the same rows.
 */
@Component
public class DeviceStore {

    /** How long a phone refused with DEVICE_MISMATCH may ask for, and follow, a change. */
    public static final Duration CHANGE_TOKEN_TTL = Duration.ofDays(3);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Clock clock;

    DeviceStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ rules

    /** A school's device rules ({@code autoRebindDays} / {@code minAppVersion} null = off). */
    public record Policy(boolean oneDevice, boolean changeRequests, Integer autoRebindDays, String minAppVersion) {

        static final Policy DEFAULTS = new Policy(true, true, null, null);
    }

    public Policy policy(UUID schoolId) {
        return jdbc.sql("select one_device, change_requests, auto_rebind_days, min_app_version from device_policy where school_id = :s")
                .param("s", schoolId)
                .query((rs, n) -> new Policy(rs.getBoolean("one_device"), rs.getBoolean("change_requests"),
                        (Integer) rs.getObject("auto_rebind_days"), rs.getString("min_app_version")))
                .optional().orElse(Policy.DEFAULTS);
    }

    // ------------------------------------------------------------------ the linked phone

    /** What the phone says about itself at login. */
    public record Phone(UUID deviceId, String name, String platform, String os, String appVersion, String publicKey, Boolean rooted) {
    }

    /** The account's linked phone, if any. */
    public record Linked(UUID deviceId, String name, Instant lastSeenAt) {
    }

    public Optional<Linked> linked(UUID accountId) {
        return jdbc.sql("select device_id, device_name, device_last_seen_at from account where id = :a and device_id is not null")
                .param("a", accountId)
                .query((rs, n) -> new Linked(rs.getObject("device_id", UUID.class), rs.getString("device_name"),
                        Optional.ofNullable(rs.getObject("device_last_seen_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null)))
                .optional();
    }

    /** Links the phone to the account (replacing any other), as last seen now. */
    public void bind(UUID accountId, Phone p) {
        OffsetDateTime now = now();
        jdbc.sql("""
                update account set device_id = :d, device_name = :n, device_platform = :pl, device_os = :os, device_app_version = :v,
                       device_public_key = :k, device_rooted = :r, device_bound_at = :now, device_last_seen_at = :now
                where id = :a""")
                .param("d", p.deviceId()).param("n", p.name()).param("pl", p.platform()).param("os", p.os()).param("v", p.appVersion())
                .param("k", p.publicKey()).param("r", p.rooted()).param("now", now).param("a", accountId).update();
        // a reset or an approved change waits for the new phone: it is this one
        jdbc.sql("""
                update device_event set new_device_name = :n
                where id = (select id from device_event where account_id = :a and kind in ('RESET', 'CHANGE_APPROVED')
                            and new_device_name is null order by at desc limit 1)""")
                .param("n", p.name() == null ? "Unnamed phone" : p.name()).param("a", accountId).update();
    }

    /** The linked phone logged in again: what it says about itself now, and when it was last seen. */
    public void touch(UUID accountId, Phone p) {
        jdbc.sql("""
                update account set device_name = coalesce(:n, device_name), device_platform = coalesce(:pl, device_platform),
                       device_os = coalesce(:os, device_os), device_app_version = coalesce(:v, device_app_version),
                       device_public_key = coalesce(:k, device_public_key), device_rooted = coalesce(:r, device_rooted),
                       device_last_seen_at = :now
                where id = :a""")
                .param("n", p.name()).param("pl", p.platform()).param("os", p.os()).param("v", p.appVersion()).param("k", p.publicKey())
                .param("r", p.rooted()).param("now", now()).param("a", accountId).update();
    }

    /** A refresh from the app: the phone is in use. */
    public void seen(String username) {
        jdbc.sql("update account set device_last_seen_at = :now where lower(username) = lower(:u) and device_id is not null")
                .param("now", now()).param("u", username).update();
    }

    public boolean blocked(UUID deviceId) {
        return jdbc.sql("select count(*) from device_block where device_id = :d").param("d", deviceId).query(Long.class).single() > 0;
    }

    public void event(UUID schoolId, UUID accountId, String kind, String oldName, String newName, String reason, UUID by) {
        jdbc.sql("""
                insert into device_event (id, school_id, account_id, kind, old_device_name, new_device_name, reason, by_account, at)
                values (:id, :s, :a, :k, :o, :n, :r, :b, :at)""")
                .param("id", UUID.randomUUID()).param("s", schoolId).param("a", accountId).param("k", kind).param("o", oldName)
                .param("n", newName).param("r", reason).param("b", by).param("at", now()).update();
    }

    // ------------------------------------------------------------------ change requests

    /** A new token for a refused phone (only its hash is kept). */
    public String newChangeToken(UUID accountId, Phone p) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime now = now();
        jdbc.sql("""
                insert into device_change_token (token_hash, account_id, device_id, device_name, platform, created_at, expires_at)
                values (:h, :a, :d, :n, :p, :now, :exp)""")
                .param("h", hash(token)).param("a", accountId).param("d", p.deviceId()).param("n", p.name()).param("p", p.platform())
                .param("now", now).param("exp", now.plus(CHANGE_TOKEN_TTL)).update();
        jdbc.sql("delete from device_change_token where expires_at < :now").param("now", now).update();
        return token;
    }

    /** The phone a change token was given to. */
    public record TokenHolder(UUID accountId, UUID schoolId, UUID deviceId, String deviceName, String platform) {
    }

    public Optional<TokenHolder> tokenHolder(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select t.account_id, a.school_id, t.device_id, t.device_name, t.platform
                from device_change_token t join account a on a.id = t.account_id
                where t.token_hash = :h and t.expires_at > :now""")
                .param("h", hash(token.trim())).param("now", now())
                .query((rs, n) -> new TokenHolder(rs.getObject("account_id", UUID.class), rs.getObject("school_id", UUID.class),
                        rs.getObject("device_id", UUID.class), rs.getString("device_name"), rs.getString("platform")))
                .optional();
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
