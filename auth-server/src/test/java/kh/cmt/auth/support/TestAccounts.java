package kh.cmt.auth.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Test helper: throw-away logins, so tests never change the shared test.staff / test.admin accounts. */
@Component
public class TestAccounts {

    static final UUID SCHOOL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public TestAccounts(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /** Creates an active app account with a unique username and returns it. */
    public String create(String prefix, String password, boolean mustChangePassword) {
        return createWithHash(prefix, encoder.encode(password), mustChangePassword);
    }

    /** A web (office) account with a role and optionally a phone for the SMS fallback. */
    public String createWeb(String prefix, String password, String role, String phone) {
        String username = prefix + "." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into account (id, school_id, username, password_hash, type, role, phone, created_at, updated_at)
                values (:id, :school, :username, :hash, 'web', :role, :phone, :now, :now)
                """)
                .param("id", UUID.randomUUID()).param("school", SCHOOL).param("username", username)
                .param("hash", encoder.encode(password)).param("role", role).param("phone", phone)
                .param("now", OffsetDateTime.now())
                .update();
        return username;
    }

    /** Turns "two-step verification required" on or off for a role. */
    public void requireTwoFactor(String role, boolean required) {
        jdbc.sql("""
                insert into role_login_rule (school_id, role, two_factor_required) values (:school, :role, :r)
                on conflict (school_id, role) do update set two_factor_required = :r
                """)
                .param("school", SCHOOL).param("role", role).param("r", required).update();
    }

    /**
     * Sets a role's login rules as the iam module would (null = no restriction). Hours as "HH:mm", days as ISO
     * weekdays, addresses or CIDR ranges.
     */
    public void setLoginRules(String role, Integer sessionMinutes, String from, String to, List<Integer> days, List<String> ips) {
        jdbc.sql("""
                insert into role_login_rule (school_id, role, session_timeout_minutes, allowed_from, allowed_to,
                                             allowed_days, allowed_ips)
                values (:school, :role, :sess, cast(:from as time), cast(:to as time), cast(:days as smallint[]),
                        cast(:ips as text[]))
                on conflict (school_id, role) do update set
                    session_timeout_minutes = excluded.session_timeout_minutes,
                    allowed_from = excluded.allowed_from, allowed_to = excluded.allowed_to,
                    allowed_days = excluded.allowed_days, allowed_ips = excluded.allowed_ips
                """)
                .param("school", SCHOOL).param("role", role).param("sess", sessionMinutes)
                .param("from", from).param("to", to)
                .param("days", days == null ? null : "{" + String.join(",", days.stream().map(String::valueOf).toList()) + "}")
                .param("ips", ips == null ? null : "{" + String.join(",", ips) + "}")
                .update();
    }

    public void clearLoginRules(String role) {
        jdbc.sql("delete from role_login_rule where school_id = :school and role = :role")
                .param("school", SCHOOL).param("role", role).update();
    }

    public String createWithHash(String prefix, String passwordHash, boolean mustChangePassword) {
        String username = prefix + "." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into account (id, school_id, username, password_hash, type, must_change_password,
                                     created_at, updated_at)
                values (:id, :school, :username, :hash, 'app', :must, :now, :now)
                """)
                .param("id", UUID.randomUUID()).param("school", SCHOOL).param("username", username)
                .param("hash", passwordHash).param("must", mustChangePassword)
                .param("now", OffsetDateTime.now())
                .update();
        return username;
    }
}
