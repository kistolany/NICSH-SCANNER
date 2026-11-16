package kh.cmt.auth.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    /**
     * What the access token and the login checks need to know about a login.
     *
     * @param accessExpiresOn   last day the account may be used (web users with an end date); null = no end
     * @param twoFactorRequired two-step verification required for this user (besides the role's rule)
     * @param sessionsRevokedAt "sign out everywhere": login sessions opened before this must sign in again
     */
    public record AccountClaims(UUID id, UUID schoolId, String type, UUID personId, UUID webUserId,
            String role, int permVer, String locale, UUID deviceId, String status,
            LocalDate accessExpiresOn, boolean twoFactorRequired, Instant sessionsRevokedAt) {

        public boolean isActive() {
            return "active".equals(status);
        }

        /** Expired from the day after {@code accessExpiresOn} (school time). */
        public boolean isExpiredOn(LocalDate today) {
            return accessExpiresOn != null && today.isAfter(accessExpiresOn);
        }
    }

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean mustChangePassword(String username) {
        return jdbc.sql("select must_change_password from account where lower(username) = lower(:u)")
                .param("u", username).query(Boolean.class).optional().orElse(false);
    }

    public Optional<AccountClaims> findByUsername(String username) {
        return jdbc.sql("""
                select id, school_id, type, person_id, web_user_id, role, perm_ver, locale, device_id, status,
                       access_expires_on, two_factor_required, sessions_revoked_at
                from account where lower(username) = lower(:username)
                """)
                .param("username", username)
                .query((rs, n) -> {
                    Timestamp revoked = rs.getTimestamp("sessions_revoked_at");
                    return new AccountClaims(
                            rs.getObject("id", UUID.class),
                            rs.getObject("school_id", UUID.class),
                            rs.getString("type"),
                            rs.getObject("person_id", UUID.class),
                            rs.getObject("web_user_id", UUID.class),
                            rs.getString("role"),
                            rs.getInt("perm_ver"),
                            rs.getString("locale"),
                            rs.getObject("device_id", UUID.class),
                            rs.getString("status"),
                            rs.getObject("access_expires_on", LocalDate.class),
                            rs.getBoolean("two_factor_required"),
                            revoked == null ? null : revoked.toInstant());
                })
                .optional();
    }

    /** A successful password login (shown as "last login" on the web users list). */
    public void recordLogin(String username, Instant at) {
        jdbc.sql("update account set last_login_at = :at where lower(username) = lower(:u)")
                .param("at", java.sql.Timestamp.from(at)).param("u", username).update();
    }
}
