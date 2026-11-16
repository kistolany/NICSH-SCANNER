package kh.cmt.auth.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.util.List;
import kh.cmt.auth.AuthProperties;
import kh.cmtlib.ratelimit.KeyBy;
import kh.cmtlib.ratelimit.RateLimitFilter;
import kh.cmtlib.ratelimit.RateLimitRule;
import kh.cmtlib.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Login-type endpoints are limited per IP address (nobody is signed in yet), before Spring Security even
 * looks at the request:
 * <ul>
 *   <li>password login: {@code POST /login}</li>
 *   <li>second step: {@code POST /login/otp}, {@code /login/otp/sms}, {@code /login/otp/enroll}</li>
 * </ul>
 * Each group has its own bucket of {@code auth.rate-limit.login.capacity} requests per window. This limit is
 * about volume from one address; the lock after 5 wrong passwords or codes (steps 1.3 / 1.4) is about one account.
 */
@Configuration
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

    /** Runs before the Spring Security filter chain (which is at order -100). */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RedisRateLimiter limiter, AuthProperties props) {
        AuthProperties.Limit login = props.rateLimit().login();
        List<RateLimitRule> rules = List.of(
                RateLimitRule.of("login", KeyBy.IP, login.capacity(), login.window(), "/login", "/auth/login").onlyMethods("POST"),
                RateLimitRule.of("second-step", KeyBy.IP, login.capacity(), login.window(),
                        "/login/otp", "/login/otp/sms", "/login/otp/enroll").onlyMethods("POST"),
                RateLimitRule.of("device-change", KeyBy.IP, login.capacity(), login.window(),
                        "/auth/device/change-request").onlyMethods("POST", "DELETE"));
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(limiter, rules, request -> null));
        registration.setOrder(-150);
        registration.setEnabled(props.rateLimit().enabled());
        return registration;
    }
}
