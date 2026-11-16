package kh.cmt.iam.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The auth server's account of every web user ({@code auth.account}), kept by the iam module in the same transaction
 * as the web user (both apps use the same database, each its own schema). Also the passwords admins give (no
 * email / SMS links) and the refresh tokens of those accounts, for "sign out everywhere".
 * <p>
 * Keep in line with the auth server's migrations (V1..V6); the cmt-api tests use a copy of these tables
 * ({@code V902__test_auth_accounts.sql}).
 */
@Repository
public class AuthAccountStore {

    /** What the account of a web user carries. */
    public record Account(UUID schoolId, UUID webUserId, String username, String role, UUID personId, String phone,
            boolean twoFactorRequired, LocalDate accessExpiresOn, boolean active) {
    }

    /** What the web users list shows from the account. */
    public record LoginInfo(Instant lastLoginAt, boolean twoFactorEnrolled) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuthAccountStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Any login (web or app, active or not) already uses this username? */
    public boolean usernameTaken(String username, UUID exceptAccountId) {
        return jdbc.sql("select count(*) from auth.account where lower(username) = lower(:u) and id <> :except")
                .param("u", username).param("except", exceptAccountId == null ? new UUID(0, 0) : exceptAccountId)
                .query(Integer.class).single() > 0;
    }

    /**
     * A new web user's account with the password the admin gave ({@code passwordHash} a full stored hash, e.g.
     * {@code {bcrypt}...}); {@code mustChangePassword}: the sign-in page asks for a new one first.
     */
    public void create(UUID accountId, Account account, String passwordHash, boolean mustChangePassword) {
        jdbc.sql("""
                        insert into auth.account (id, school_id, username, password_hash, type, person_id, web_user_id,
                                                  status, role, phone, two_factor_required, access_expires_on,
                                                  must_change_password, created_at, updated_at)
                        values (:id, :school, :username, :hash, 'web', :person, :webUser, :status, :role, :phone, :tfa,
                                :expires, :must, :now, :now)""")
                .param("id", accountId).param("school", account.schoolId()).param("username", account.username())
                .param("hash", passwordHash).param("person", account.personId()).param("webUser", account.webUserId())
                .param("status", account.active() ? "active" : "inactive").param("role", account.role())
                .param("phone", account.phone()).param("tfa", account.twoFactorRequired())
                .param("expires", account.accessExpiresOn()).param("must", mustChangePassword).param("now", now())
                .update();
    }

    public void update(UUID accountId, Account account) {
        jdbc.sql("""
                        update auth.account set username = :username, person_id = :person, status = :status, role = :role,
                                                phone = :phone, two_factor_required = :tfa, access_expires_on = :expires,
                                                updated_at = :now, version = version + 1
                        where id = :id""")
                .param("id", accountId).param("username", account.username()).param("person", account.personId())
                .param("status", account.active() ? "active" : "inactive").param("role", account.role())
                .param("phone", account.phone()).param("tfa", account.twoFactorRequired())
                .param("expires", account.accessExpiresOn()).param("now", now())
                .update();
    }

    /**
     * Ends every login of the account: its refresh tokens are revoked, the auth server's stored sessions removed, and
     * a browser that is still signed in to the auth server must enter the password again. Returns the number of
     * login sessions that were open.
     */
    public int signOutEverywhere(UUID accountId) {
        Timestamp now = now();
        int sessions = jdbc.sql("""
                        select count(distinct family_id) from auth.refresh_token
                        where account_id = :a and revoked_at is null and expires_at > :now""")
                .param("a", accountId).param("now", now).query(Integer.class).single();
        jdbc.sql("update auth.refresh_token set revoked_at = :now where account_id = :a and revoked_at is null")
                .param("now", now).param("a", accountId).update();
        jdbc.sql("""
                        delete from auth.oauth2_authorization
                        where principal_name = (select username from auth.account where id = :a)""")
                .param("a", accountId).update();
        jdbc.sql("update auth.account set sessions_revoked_at = :now where id = :a").param("now", now).param("a", accountId).update();
        return sessions;
    }

    /** The school's rules for new passwords (auth server table {@code password_policy}); the defaults if none. */
    public record PasswordPolicy(int minLength, boolean upper, boolean lower, boolean digit, boolean symbol) {
    }

    public PasswordPolicy passwordPolicy(UUID schoolId) {
        return jdbc.sql("""
                        select min_length, require_upper, require_lower, require_digit, require_symbol
                        from auth.password_policy where school_id = :s""")
                .param("s", schoolId)
                .query((rs, n) -> new PasswordPolicy(rs.getInt(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)))
                .optional().orElse(new PasswordPolicy(8, true, false, true, false));
    }

    /** What the person page shows about an app account. */
    public record AppLogin(UUID accountId, String username, boolean active, boolean mustChangePassword, Instant lastLoginAt) {
    }

    public java.util.Optional<AppLogin> appLoginOf(UUID personId) {
        return jdbc.sql("""
                        select id, username, status, must_change_password, last_login_at
                        from auth.account where person_id = :p and type = 'app'""")
                .param("p", personId)
                .query((rs, n) -> {
                    Timestamp last = rs.getTimestamp("last_login_at");
                    return new AppLogin(rs.getObject("id", UUID.class), rs.getString("username"), "active".equals(rs.getString("status")),
                            rs.getBoolean("must_change_password"), last == null ? null : last.toInstant());
                })
                .optional();
    }

    /** A person's app account; {@code passwordHash} is a full stored hash, e.g. {@code {bcrypt}...}. */
    public void createApp(UUID accountId, UUID schoolId, UUID personId, String username, String passwordHash, String role,
            String phone, boolean mustChangePassword, boolean active) {
        jdbc.sql("""
                        insert into auth.account (id, school_id, username, password_hash, type, person_id, status, role, phone,
                                                  must_change_password, created_at, updated_at)
                        values (:id, :school, :username, :hash, 'app', :person, :status, :role, :phone, :must, :now, :now)""")
                .param("id", accountId).param("school", schoolId).param("username", username).param("hash", passwordHash)
                .param("person", personId).param("status", active ? "active" : "inactive").param("role", role)
                .param("phone", phone).param("must", mustChangePassword).param("now", now())
                .update();
    }

    /** A password an admin gave (web or app account); the lock after wrong passwords is lifted. */
    public void setPassword(UUID accountId, String passwordHash, boolean mustChangePassword) {
        jdbc.sql("""
                        update auth.account set password_hash = :hash, must_change_password = :must, locked_until = null,
                                                failed_attempts = 0, updated_at = :now, version = version + 1
                        where id = :a""")
                .param("hash", passwordHash).param("must", mustChangePassword).param("now", now()).param("a", accountId).update();
    }

    public void updateApp(UUID accountId, String role, String phone) {
        jdbc.sql("update auth.account set role = :role, phone = :phone, updated_at = :now, version = version + 1 where id = :a")
                .param("role", role).param("phone", phone).param("now", now()).param("a", accountId).update();
    }

    /** The app account of a person (type app, the person's own login), if there is one. */
    public java.util.Optional<UUID> appAccountOf(UUID personId) {
        return jdbc.sql("select id from auth.account where person_id = :p and type = 'app'").param("p", personId)
                .query(UUID.class).optional();
    }

    public void setActive(UUID accountId, boolean active) {
        jdbc.sql("update auth.account set status = :s, updated_at = :now, version = version + 1 where id = :a")
                .param("s", active ? "active" : "inactive").param("now", now()).param("a", accountId).update();
    }

    /** Removes the authenticator app (lost phone); the user enrols again at the next login. */
    public void resetTwoFactor(UUID accountId) {
        jdbc.sql("update auth.account set totp_secret_enc = null, totp_enabled_at = null, totp_last_step = null where id = :a")
                .param("a", accountId).update();
    }

    /** Last login and two-step enrolment of these accounts, by account id. */
    public Map<UUID, LoginInfo> loginInfo(Collection<UUID> accountIds) {
        Map<UUID, LoginInfo> all = new HashMap<>();
        if (accountIds.isEmpty()) {
            return all;
        }
        jdbc.sql("select id, last_login_at, totp_enabled_at is not null as enrolled from auth.account where id in (:ids)")
                .param("ids", accountIds)
                .query((rs, n) -> {
                    Timestamp last = rs.getTimestamp("last_login_at");
                    return all.put(rs.getObject("id", UUID.class),
                            new LoginInfo(last == null ? null : last.toInstant(), rs.getBoolean("enrolled")));
                })
                .list();
        return all;
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().atOffset(ZoneOffset.UTC).toInstant());
    }

}
