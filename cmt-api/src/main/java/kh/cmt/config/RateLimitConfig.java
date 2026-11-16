package kh.cmt.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import kh.cmtlib.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Rate limiting needs its own small Redis client (the shared library works on Lettuce directly); it is built
 * from the same {@code spring.data.redis.*} settings as everything else and connects on first use.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    /**
     * Host, port and password from {@link RedisConnectionDetails}, like Spring's own Redis client: that is where test
     * containers ({@code @ServiceConnection}) put them; the plain properties would still say localhost:6379.
     */
    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(RedisConnectionDetails connection, RedisProperties redis) {
        RedisConnectionDetails.Standalone server = connection.getStandalone();
        RedisURI.Builder uri = RedisURI.builder().withHost(server.getHost()).withPort(server.getPort())
                .withDatabase(server.getDatabase());
        if (StringUtils.hasText(connection.getPassword())) {
            uri.withPassword(connection.getPassword().toCharArray());
        }
        if (redis.getSsl().isEnabled()) {
            uri.withSsl(true);
        }
        return RedisClient.create(uri.build());
    }

    @Bean
    RedisRateLimiter redisRateLimiter(RedisClient rateLimitRedisClient) {
        return new RedisRateLimiter(rateLimitRedisClient);
    }
}
