package kh.cmt.auth.account;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a login from the {@code account} table. Authority is ROLE_APP or ROLE_WEB from the account type. Refused:
 * inactive accounts, locked accounts, and accounts after their {@code access_expires_on} day.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountUserDetailsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Called by Spring after a successful login when the stored hash uses an older algorithm (e.g. bcrypt). */
    @Override
    public UserDetails updatePassword(UserDetails user, String newEncodedPassword) {
        jdbc.sql("update account set password_hash = :hash where lower(username) = lower(:u)")
                .param("hash", newEncodedPassword).param("u", user.getUsername()).update();
        return User.withUserDetails(user).password(newEncodedPassword).build();
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("""
                select username, password_hash, type, status, locked_until, access_expires_on
                from account where lower(username) = lower(:username)
                """)
                .param("username", username)
                .query((rs, n) -> {
                    Timestamp lockedUntil = rs.getTimestamp("locked_until");
                    boolean locked = lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now(clock));
                    // a web user's access ends after its last day (school time): from the next day no login
                    LocalDate lastDay = rs.getObject("access_expires_on", LocalDate.class);
                    boolean expired = lastDay != null && LocalDate.now(clock).isAfter(lastDay);
                    return User.withUsername(rs.getString("username"))
                            .password(rs.getString("password_hash"))
                            .authorities("ROLE_" + rs.getString("type").toUpperCase())
                            .disabled(!"active".equals(rs.getString("status")))
                            .accountLocked(locked)
                            .accountExpired(expired)
                            .build();
                })
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
