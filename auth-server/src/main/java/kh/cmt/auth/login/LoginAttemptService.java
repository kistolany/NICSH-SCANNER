package kh.cmt.auth.login;

import java.time.Clock;
import java.time.ZoneOffset;
import kh.cmt.auth.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Wrong-password counter. The count lives in Redis (key expires after the window); on reaching the limit the
 * account gets {@code locked_until} in the database, which the login checks. A correct login resets the count.
 * If Redis is down the login still works (lockout is then not enforced) and the error is logged.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String KEY_PREFIX = "auth:login-failures:";
    private static final String OTP_KEY_PREFIX = "auth:otp-failures:";

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuthProperties.Lockout lockout;

    public LoginAttemptService(StringRedisTemplate redis, JdbcClient jdbc, Clock clock, AuthProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.clock = clock;
        this.lockout = props.lockout();
    }

    public void recordFailure(String username) {
        try {
            String key = key(username);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, lockout.window());
            }
            if (count != null && count >= lockout.maxAttempts()) {
                lock(username);
                redis.delete(key);
            }
        } catch (RuntimeException e) {
            log.error("Could not record failed login for lockout (is Redis down?)", e);
        }
    }

    /**
     * Wrong one-time code. Counted apart from passwords (a correct password must not reset it), same limit and
     * same lock. Returns true when this failure locked the account.
     */
    public boolean recordOtpFailure(String username) {
        try {
            String key = OTP_KEY_PREFIX + username.toLowerCase();
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, lockout.window());
            }
            if (count != null && count >= lockout.maxAttempts()) {
                lock(username);
                redis.delete(key);
                return true;
            }
        } catch (RuntimeException e) {
            log.error("Could not record failed one-time code (is Redis down?)", e);
        }
        return false;
    }

    public void clearOtp(String username) {
        try {
            redis.delete(OTP_KEY_PREFIX + username.toLowerCase());
        } catch (RuntimeException e) {
            log.error("Could not clear one-time code failure counter", e);
        }
    }

    public void clear(String username) {
        try {
            redis.delete(key(username));
        } catch (RuntimeException e) {
            log.error("Could not clear login failure counter", e);
        }
        jdbc.sql("update account set failed_attempts = 0 where lower(username) = lower(:u) and failed_attempts <> 0")
                .param("u", username).update();
    }

    /** Also used by the password reset, which lifts a lock. */
    public void unlock(String username) {
        clear(username);
        jdbc.sql("update account set locked_until = null where lower(username) = lower(:u)")
                .param("u", username).update();
    }

    private void lock(String username) {
        int updated = jdbc.sql("""
                update account set locked_until = :until, failed_attempts = :attempts
                where lower(username) = lower(:u)
                """)
                .param("until", clock.instant().plus(lockout.lockDuration()).atOffset(ZoneOffset.UTC))
                .param("attempts", lockout.maxAttempts())
                .param("u", username).update();
        if (updated > 0) {
            log.warn("Account '{}' locked for {} after {} wrong passwords", username, lockout.lockDuration(),
                    lockout.maxAttempts());
        }
    }

    private static String key(String username) {
        return KEY_PREFIX + username.toLowerCase();
    }
}
