package kh.cmtlib.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets in Redis (Bucket4j). The bucket state lives in Redis, so every instance of an application
 * shares the same counters.
 * <p>
 * An unavailable limiter must not take the service down, so every problem with Redis lets the request through
 * and is logged: the connection is made on first use (the application starts even if Redis is down), each
 * command has a short timeout, and after a failure Redis is left alone for a few seconds instead of making
 * every request wait for a new connection attempt.
 */
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(250);
    private static final long BACK_OFF_NANOS = Duration.ofSeconds(5).toNanos();

    /** Result of one check. {@code retryAfter} is only meaningful when {@code allowed} is false. */
    public record Decision(boolean allowed, long remaining, Duration retryAfter) {

        static Decision allow(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }
    }

    private final RedisClient redis;
    private StatefulRedisConnection<byte[], byte[]> connection;
    private LettuceBasedProxyManager<byte[]> buckets;
    private long retryAtNanos;   // 0 = no back-off

    public RedisRateLimiter(RedisClient redis) {
        this.redis = redis;
    }

    /** Takes one token from the bucket of {@code key} under {@code rule}. */
    public Decision tryConsume(RateLimitRule rule, String key) {
        try {
            LettuceBasedProxyManager<byte[]> manager = manager();
            if (manager == null) {
                return Decision.allow(-1);   // Redis was unreachable a moment ago
            }
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(rule.capacity()).refillGreedy(rule.capacity(), rule.window()))
                    .build();
            byte[] redisKey = ("rl:" + rule.name() + ":" + key).getBytes(StandardCharsets.UTF_8);
            ConsumptionProbe probe = manager.getProxy(redisKey, () -> configuration).tryConsumeAndReturnRemaining(1);
            return probe.isConsumed()
                    ? Decision.allow(probe.getRemainingTokens())
                    : new Decision(false, 0, Duration.ofNanos(probe.getNanosToWaitForRefill()));
        } catch (RuntimeException e) {
            log.error("Rate limiter unavailable, letting requests through for a few seconds (rule '{}'): {}",
                    rule.name(), e.toString());
            backOff();
            return Decision.allow(-1);
        }
    }

    private synchronized LettuceBasedProxyManager<byte[]> manager() {
        if (retryAtNanos != 0 && System.nanoTime() - retryAtNanos < 0) {
            return null;
        }
        if (buckets == null) {
            connection = redis.connect(ByteArrayCodec.INSTANCE);
            connection.setTimeout(COMMAND_TIMEOUT);
            buckets = LettuceBasedProxyManager.builderFor(connection)
                    .withExpirationStrategy(ExpirationAfterWriteStrategy.fixedTimeToLive(Duration.ofHours(1)))
                    .build();
        }
        retryAtNanos = 0;
        return buckets;
    }

    private synchronized void backOff() {
        retryAtNanos = System.nanoTime() + BACK_OFF_NANOS;
        if (retryAtNanos == 0) {
            retryAtNanos = 1;
        }
    }
}
