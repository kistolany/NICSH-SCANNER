package kh.cmt.auth.password;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kh.cmt.auth.login.LoginAttemptService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user changes their own password. There is no "forgot password" by email or SMS: an admin gives a new
 * password (Admin Web / admin app), which must usually be changed at the next login. Every refusal is a
 * {@link PasswordException} with a stable code.
 */
@Service
public class PasswordService {

    private record Credentials(UUID id, String username, String passwordHash) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicyService policy;
    private final LoginAttemptService attempts;
    private final Clock clock;

    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, PasswordPolicyService policy,
            LoginAttemptService attempts, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policy = policy;
        this.attempts = attempts;
        this.clock = clock;
    }

    /**
     * The signed-in user changes their own password. Wrong current passwords count towards the lockout, so a
     * stolen access token cannot be used to guess the password. Other sessions are not ended.
     */
    @Transactional(noRollbackFor = PasswordException.class)   // a wrong password must still count towards the lockout
    public void change(UUID accountId, String currentPassword, String newPassword) {
        Credentials account = jdbc.sql("select id, username, password_hash from account where id = :id")
                .param("id", accountId)
                .query((rs, n) -> new Credentials(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("password_hash")))
                .optional()
                .orElseThrow(() -> new PasswordException(PasswordException.FORBIDDEN, HttpStatus.FORBIDDEN));

        if (!encoder.matches(currentPassword, account.passwordHash())) {
            attempts.recordFailure(account.username());
            throw new PasswordException(PasswordException.WRONG_PASSWORD, HttpStatus.BAD_REQUEST);
        }
        List<String> violations = policy.violations(account.username(), newPassword);
        if (encoder.matches(newPassword, account.passwordHash())) {
            violations = append(violations, "SAME_AS_CURRENT");
        }
        failIfAny(violations);
        store(accountId, newPassword);
    }

    /**
     * The forced change right after signing in with a given password (first login, or after an admin reset): the login
     * session has just proved the current password, so it is not asked again. Only for accounts that must change it.
     */
    @Transactional
    public void changeAfterLogin(String username, String newPassword) {
        record Forced(UUID id, String username, String passwordHash, boolean mustChange) {
        }
        Forced account = jdbc.sql("select id, username, password_hash, must_change_password from account where lower(username) = lower(:u)")
                .param("u", username)
                .query((rs, n) -> new Forced(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                        rs.getBoolean("must_change_password")))
                .optional()
                .filter(Forced::mustChange)
                .orElseThrow(() -> new PasswordException(PasswordException.FORBIDDEN, HttpStatus.FORBIDDEN));
        List<String> violations = policy.violations(account.username(), newPassword);
        if (encoder.matches(newPassword, account.passwordHash())) {
            violations = append(violations, "SAME_AS_CURRENT");
        }
        failIfAny(violations);
        store(account.id(), newPassword);
    }

    /** Writes the new hash, clears must_change_password. */
    private void store(UUID accountId, String newPassword) {
        jdbc.sql("""
                update account set password_hash = :hash, must_change_password = false,
                                   password_changed_at = :now, updated_at = :now, version = version + 1
                where id = :id
                """)
                .param("hash", encoder.encode(newPassword))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("id", accountId).update();
    }

    private static void failIfAny(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new PasswordException(PasswordException.PASSWORD_POLICY, HttpStatus.BAD_REQUEST)
                    .with("violations", violations);
        }
    }

    private static List<String> append(List<String> list, String item) {
        List<String> copy = new java.util.ArrayList<>(list);
        copy.add(item);
        return copy;
    }

}
