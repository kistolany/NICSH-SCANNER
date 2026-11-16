package kh.cmt.devices.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The app accounts' phones in the auth server's tables (auth-server V9): linked phone, blocked phones, change
 * requests, history, the school's device rules. The auth server uses the same rows at login.
 */
@Repository
public class AuthDeviceStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    AuthDeviceStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ phones of people

    /** A person's app account and its phone ({@code deviceId} null: none linked). */
    public record AccountDevice(UUID accountId, UUID personId, UUID deviceId, String name, String platform, String os, String appVersion,
            Instant boundAt, Instant lastSeenAt, Boolean rooted, boolean hasPublicKey, String blockReason, UUID blockedBy, Instant blockedAt,
            UUID openRequestId) {

        public boolean blocked() {
            return blockedAt != null;
        }
    }

    private static final String ACCOUNT_DEVICE = """
            select a.id, a.person_id, a.device_id, a.device_name, a.device_platform, a.device_os, a.device_app_version,
                   a.device_bound_at, a.device_last_seen_at, a.device_rooted, a.device_public_key is not null as has_key,
                   b.reason, b.blocked_by, b.blocked_at,
                   (select r.id from auth.device_change_request r where r.account_id = a.id and r.status in ('pending', 'seen')
                    limit 1) as open_request
            from auth.account a left join auth.device_block b on b.device_id = a.device_id
            where a.type = 'app'""";

    /** The app accounts of these people of the school, by person. */
    public Map<UUID, AccountDevice> ofPeople(UUID schoolId, Collection<UUID> personIds) {
        Map<UUID, AccountDevice> out = new HashMap<>();
        if (personIds.isEmpty()) {
            return out;
        }
        jdbc.sql(ACCOUNT_DEVICE + " and a.school_id = :s and a.person_id in (:ids)").param("s", schoolId).param("ids", personIds)
                .query((rs, n) -> accountDevice(rs)).list().forEach(d -> out.put(d.personId(), d));
        return out;
    }

    /** The linked phone's key for signed scans, if the app sent one. */
    public Optional<String> publicKey(UUID accountId) {
        return jdbc.sql("select device_public_key from auth.account where id = :a and device_public_key is not null")
                .param("a", accountId).query(String.class).optional();
    }

    public Optional<AccountDevice> ofAccount(UUID accountId) {
        return jdbc.sql(ACCOUNT_DEVICE + " and a.id = :a").param("a", accountId).query((rs, n) -> accountDevice(rs)).optional();
    }

    private static AccountDevice accountDevice(ResultSet rs) throws SQLException {
        return new AccountDevice(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getString("device_name"), rs.getString("device_platform"), rs.getString("device_os"), rs.getString("device_app_version"),
                instant(rs, "device_bound_at"), instant(rs, "device_last_seen_at"), (Boolean) rs.getObject("device_rooted"),
                rs.getBoolean("has_key"), rs.getString("reason"), rs.getObject("blocked_by", UUID.class), instant(rs, "blocked_at"),
                rs.getObject("open_request", UUID.class));
    }

    /** The phone stops being linked: the next phone that logs in is linked. Open change requests are cancelled. */
    public void unlink(UUID accountId) {
        jdbc.sql("""
                update auth.account set device_id = null, device_name = null, device_platform = null, device_os = null,
                       device_app_version = null, device_public_key = null, device_rooted = null, device_bound_at = null,
                       device_last_seen_at = null
                where id = :a""").param("a", accountId).update();
        jdbc.sql("update auth.device_change_request set status = 'cancelled', decided_at = :now where account_id = :a and status in ('pending', 'seen')")
                .param("now", now()).param("a", accountId).update();
    }

    /** Links this phone (an approved change request); what it says about itself comes at its first login. */
    public void link(UUID accountId, UUID deviceId, String name, String platform) {
        jdbc.sql("""
                update auth.account set device_id = :d, device_name = :n, device_platform = :p, device_os = null,
                       device_app_version = null, device_public_key = null, device_rooted = null, device_bound_at = :now,
                       device_last_seen_at = null
                where id = :a""").param("d", deviceId).param("n", name).param("p", platform).param("now", now()).param("a", accountId).update();
    }

    /** What the linked phone says about itself (POST /app/device/register). */
    public void describe(UUID accountId, String name, String platform, String os, String appVersion, String publicKey, Boolean rooted) {
        jdbc.sql("""
                update auth.account set device_name = coalesce(:n, device_name), device_platform = coalesce(:pl, device_platform),
                       device_os = coalesce(:os, device_os), device_app_version = coalesce(:v, device_app_version),
                       device_public_key = coalesce(:k, device_public_key), device_rooted = coalesce(:r, device_rooted),
                       device_last_seen_at = :now
                where id = :a""")
                .param("n", name).param("pl", platform).param("os", os).param("v", appVersion).param("k", publicKey).param("r", rooted)
                .param("now", now()).param("a", accountId).update();
    }

    public void block(UUID schoolId, UUID accountId, UUID deviceId, String name, String reason, UUID by) {
        jdbc.sql("""
                insert into auth.device_block (device_id, school_id, account_id, device_name, reason, blocked_by, blocked_at)
                values (:d, :s, :a, :n, :r, :b, :now) on conflict (device_id) do nothing""")
                .param("d", deviceId).param("s", schoolId).param("a", accountId).param("n", name).param("r", reason).param("b", by)
                .param("now", now()).update();
    }

    public int unblock(UUID deviceId) {
        return jdbc.sql("delete from auth.device_block where device_id = :d").param("d", deviceId).update();
    }

    // ------------------------------------------------------------------ change requests

    public record ChangeRequest(UUID id, UUID accountId, UUID personId, String status, String reason, String oldDeviceName,
            UUID newDeviceId, String newDeviceName, String newPlatform, Instant createdAt, Instant seenAt, Instant decidedAt, UUID decidedBy,
            String rejectionReason) {
    }

    private static final String REQUEST = """
            select r.id, r.account_id, a.person_id, r.status, r.reason, r.old_device_name, r.new_device_id, r.new_device_name,
                   r.new_platform, r.created_at, r.seen_at, r.decided_at, r.decided_by, r.rejection_reason
            from auth.device_change_request r join auth.account a on a.id = r.account_id""";

    /** The school's requests, newest first; {@code open} = pending or seen only. */
    public List<ChangeRequest> requests(UUID schoolId, boolean open, int limit) {
        return jdbc.sql(REQUEST + " where r.school_id = :s" + (open ? " and r.status in ('pending', 'seen')" : "")
                        + " order by r.created_at desc limit :l")
                .param("s", schoolId).param("l", limit).query((rs, n) -> request(rs)).list();
    }

    public Optional<ChangeRequest> request(UUID schoolId, UUID id) {
        return jdbc.sql(REQUEST + " where r.school_id = :s and r.id = :id").param("s", schoolId).param("id", id)
                .query((rs, n) -> request(rs)).optional();
    }

    private static ChangeRequest request(ResultSet rs) throws SQLException {
        return new ChangeRequest(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getString("status"), rs.getString("reason"), rs.getString("old_device_name"), rs.getObject("new_device_id", UUID.class),
                rs.getString("new_device_name"), rs.getString("new_platform"), instant(rs, "created_at"), instant(rs, "seen_at"),
                instant(rs, "decided_at"), rs.getObject("decided_by", UUID.class), rs.getString("rejection_reason"));
    }

    /** An admin has looked at the open requests: the phone shows "Seen by admin". */
    public void markSeen(Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("update auth.device_change_request set status = 'seen', seen_at = :now where id in (:ids) and status = 'pending'")
                    .param("now", now()).param("ids", ids).update();
        }
    }

    /** Ends an open request ({@code approved} / {@code rejected}); false when it was no longer open. */
    public boolean decide(UUID id, String status, UUID by, String rejectionReason) {
        return jdbc.sql("""
                update auth.device_change_request set status = :st, decided_at = :now, decided_by = :b, rejection_reason = :rr
                where id = :id and status in ('pending', 'seen')""")
                .param("st", status).param("now", now()).param("b", by).param("rr", rejectionReason).param("id", id).update() == 1;
    }

    // ------------------------------------------------------------------ history

    public record Event(UUID id, UUID accountId, UUID personId, String kind, String oldDeviceName, String newDeviceName, String reason,
            UUID by, Instant at) {
    }

    public void event(UUID schoolId, UUID accountId, String kind, String oldName, String newName, String reason, UUID by) {
        jdbc.sql("""
                insert into auth.device_event (id, school_id, account_id, kind, old_device_name, new_device_name, reason, by_account, at)
                values (:id, :s, :a, :k, :o, :n, :r, :b, :at)""")
                .param("id", UUID.randomUUID()).param("s", schoolId).param("a", accountId).param("k", kind).param("o", oldName)
                .param("n", newName).param("r", reason).param("b", by).param("at", now()).update();
    }

    /** History of the school, newest first, of these kinds. */
    public List<Event> events(UUID schoolId, Collection<String> kinds, int offset, int limit) {
        return jdbc.sql("""
                select e.id, e.account_id, a.person_id, e.kind, e.old_device_name, e.new_device_name, e.reason, e.by_account, e.at
                from auth.device_event e join auth.account a on a.id = e.account_id
                where e.school_id = :s and e.kind in (:k) order by e.at desc offset :o limit :l""")
                .param("s", schoolId).param("k", kinds).param("o", offset).param("l", limit)
                .query((rs, n) -> new Event(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getObject("person_id", UUID.class), rs.getString("kind"), rs.getString("old_device_name"),
                        rs.getString("new_device_name"), rs.getString("reason"), rs.getObject("by_account", UUID.class), instant(rs, "at")))
                .list();
    }

    public long countEvents(UUID schoolId, Collection<String> kinds, Instant since) {
        return jdbc.sql("select count(*) from auth.device_event where school_id = :s and kind in (:k) and at >= :since")
                .param("s", schoolId).param("k", kinds).param("since", since.atOffset(ZoneOffset.UTC)).query(Long.class).single();
    }

    // ------------------------------------------------------------------ the school's rules

    public record Policy(boolean oneDevice, boolean changeRequests, Integer autoRebindDays, String minAppVersion, boolean blockRooted,
            boolean blockMockLocation, long version) {

        public static final Policy DEFAULTS = new Policy(true, true, null, null, true, true, 0);
    }

    public Policy policy(UUID schoolId) {
        return jdbc.sql("""
                select one_device, change_requests, auto_rebind_days, min_app_version, block_rooted, block_mock_location, version
                from auth.device_policy where school_id = :s""")
                .param("s", schoolId)
                .query((rs, n) -> new Policy(rs.getBoolean("one_device"), rs.getBoolean("change_requests"), (Integer) rs.getObject("auto_rebind_days"),
                        rs.getString("min_app_version"), rs.getBoolean("block_rooted"), rs.getBoolean("block_mock_location"), rs.getLong("version")))
                .optional().orElse(Policy.DEFAULTS);
    }

    /** Saves the rules if {@code expectedVersion} is the stored one (0 for a school that has none yet); false otherwise. */
    public boolean savePolicy(UUID schoolId, Policy p, long expectedVersion, UUID by) {
        if (expectedVersion == 0) {
            int inserted = jdbc.sql("""
                    insert into auth.device_policy (school_id, one_device, change_requests, auto_rebind_days, min_app_version, block_rooted,
                                                    block_mock_location, updated_at, updated_by, version)
                    values (:s, :o, :c, :r, :m, :br, :bm, :now, :b, 1) on conflict (school_id) do nothing""")
                    .param("s", schoolId).param("o", p.oneDevice()).param("c", p.changeRequests()).param("r", p.autoRebindDays())
                    .param("m", p.minAppVersion()).param("br", p.blockRooted()).param("bm", p.blockMockLocation()).param("now", now())
                    .param("b", by).update();
            if (inserted == 1) {
                return true;
            }
        }
        return jdbc.sql("""
                update auth.device_policy set one_device = :o, change_requests = :c, auto_rebind_days = :r, min_app_version = :m,
                       block_rooted = :br, block_mock_location = :bm, updated_at = :now, updated_by = :b, version = version + 1
                where school_id = :s and version = :v""")
                .param("s", schoolId).param("o", p.oneDevice()).param("c", p.changeRequests()).param("r", p.autoRebindDays())
                .param("m", p.minAppVersion()).param("br", p.blockRooted()).param("bm", p.blockMockLocation()).param("now", now())
                .param("b", by).param("v", expectedVersion).update() == 1;
    }

    // ------------------------------------------------------------------ helpers

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
