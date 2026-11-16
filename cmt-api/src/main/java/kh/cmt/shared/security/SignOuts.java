package kh.cmt.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "Signed out everywhere" (deactivated, forced logout): access tokens issued before that moment are refused at
 * once, instead of staying valid until they expire. The refresh tokens are revoked on the auth server; this covers the
 * at most 15 minutes an access token lives.
 * <p>
 * Redis key {@code signout:{account}} = the moment (epoch seconds), kept as long as an access token lives. A token is
 * refused when it was issued in an earlier second. If Redis cannot be reached the check is skipped (logged): the
 * tokens then end when they expire.
 */
@Component
public class SignOuts {

    private static final Logger log = LoggerFactory.getLogger(SignOuts.class);
    private static final String KEY = "signout:";

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration remember;

    SignOuts(StringRedisTemplate redis, Clock clock, @Value("${cmt.security.access-token-ttl:15m}") Duration accessTokenTtl) {
        this.redis = redis;
        this.clock = clock;
        this.remember = accessTokenTtl.plusMinutes(1);   // plus the clock difference the validation tolerates
    }

    /** Records the sign-out of an account; inside a transaction, once it is committed. */
    public void signOut(UUID accountId) {
        long at = clock.instant().getEpochSecond();
        Runnable write = () -> {
            try {
                redis.opsForValue().set(KEY + accountId, String.valueOf(at), remember);
            } catch (RuntimeException redisProblem) {
                log.error("Could not record the sign-out of account {}: its access tokens stay valid until they expire",
                        accountId, redisProblem);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    /** Was this account signed out after the token was issued? */
    public boolean signedOutSince(String subject, Instant issuedAt) {
        if (subject == null || issuedAt == null) {
            return false;
        }
        try {
            String at = redis.opsForValue().get(KEY + subject);
            return at != null && issuedAt.getEpochSecond() < Long.parseLong(at);
        } catch (RuntimeException redisProblem) {
            log.error("Sign-out check unavailable (Redis), token of {} accepted", subject, redisProblem);
            return false;
        }
    }
}
