package kh.cmt.iam.infrastructure;

import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.iam.domain.LoginRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the auth server's table {@code auth.role_login_rule} in line with the login rules of the roles. The auth
 * server checks it at login and token refresh and puts the rules into the tokens for the BFF. Both apps use the same
 * database (own schemas), so the copy is written in the same transaction as the change: they never disagree.
 * <p>
 * At start-up every role is copied once more, which fills in roles that existed before the table did. If the auth
 * server has not created its table yet, copies are skipped (with a warning) and done at the next start.
 */
@Component
public class AuthLoginRuleSync {

    private static final Logger log = LoggerFactory.getLogger(AuthLoginRuleSync.class);

    private final JdbcClient jdbc;
    private final RoleAccessStore access;
    private volatile boolean ready;

    AuthLoginRuleSync(JdbcClient jdbc, RoleAccessStore access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public void copy(UUID schoolId, String roleCode, LoginRules rules) {
        if (!ready()) {
            log.warn("auth.role_login_rule is not there yet (auth server not migrated): login rules of {} are copied at the next start", roleCode);
            return;
        }
        // Postgres array literals: days are numbers, addresses were checked when saved (no quotes or commas in them)
        String days = rules.allowedDays().stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        String ips = rules.allowedIps().stream().map(ip -> "\"" + ip + "\"").collect(Collectors.joining(",", "{", "}"));
        jdbc.sql("""
                        insert into auth.role_login_rule (school_id, role, two_factor_required, session_timeout_minutes,
                                                          allowed_from, allowed_to, allowed_days, allowed_ips, updated_at)
                        values (:s, :r, :tfa, :sess, :from, :to, cast(:days as smallint[]), cast(:ips as text[]), now())
                        on conflict (school_id, role) do update set
                            two_factor_required = excluded.two_factor_required,
                            session_timeout_minutes = excluded.session_timeout_minutes,
                            allowed_from = excluded.allowed_from, allowed_to = excluded.allowed_to,
                            allowed_days = excluded.allowed_days, allowed_ips = excluded.allowed_ips,
                            updated_at = now()""")
                .param("s", schoolId).param("r", roleCode).param("tfa", rules.twoFactorRequired())
                .param("sess", rules.sessionTimeoutMinutes())
                .param("from", rules.allowedFrom()).param("to", rules.allowedTo())
                .param("days", days).param("ips", ips)
                .update();
    }

    /** A deleted role has no rules any more (and nobody can sign in with it). */
    public void remove(UUID schoolId, String roleCode) {
        if (!ready()) {
            return;
        }
        jdbc.sql("delete from auth.role_login_rule where school_id = :s and role = :r")
                .param("s", schoolId).param("r", roleCode).update();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void copyAll() {
        if (!ready()) {
            log.warn("auth.role_login_rule is not there yet (auth server not migrated): login rules are copied at the next start");
            return;
        }
        int copied = 0;
        for (var row : jdbc.sql("select r.school_id, r.id, r.code from role r where not r.inactive")
                .query((rs, n) -> new Object[] {rs.getObject("school_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("code")})
                .list()) {
            copy((UUID) row[0], (String) row[2], access.loginRules((UUID) row[1]));
            copied++;
        }
        log.info("Login rules of {} roles copied to the auth server", copied);
    }

    /**
     * Whether the auth server's table (with the V5 columns) exists. Checked first because a failing statement would
     * spoil the caller's transaction; remembered once it is there.
     */
    private boolean ready() {
        if (!ready) {
            ready = jdbc.sql("""
                            select count(*) from information_schema.columns
                            where table_schema = 'auth' and table_name = 'role_login_rule' and column_name = 'allowed_ips'""")
                    .query(Integer.class).single() > 0;
        }
        return ready;
    }

}
