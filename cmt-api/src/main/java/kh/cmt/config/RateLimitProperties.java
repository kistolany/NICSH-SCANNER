package kh.cmt.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code cmt.rate-limit.*}: how many requests a caller may make per window. */
@ConfigurationProperties("cmt.rate-limit")
public record RateLimitProperties(boolean enabled, Limit general, Limit scan) {

    public record Limit(long capacity, Duration window) {
    }
}
