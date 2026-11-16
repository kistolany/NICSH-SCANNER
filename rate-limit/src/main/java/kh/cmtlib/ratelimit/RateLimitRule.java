package kh.cmtlib.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.util.AntPathMatcher;

/**
 * "At most {@code capacity} requests per {@code window}" for the requests matching {@code methods} and
 * {@code paths}. Tokens come back gradually (a limit of 10 per minute gives one token every 6 seconds), so
 * there is no cliff at the end of a minute.
 *
 * @param name     short name, part of the Redis key and of log lines
 * @param methods  HTTP methods the rule applies to; empty = all
 * @param paths    Ant-style path patterns, e.g. {@code /api/**}
 * @param keyBy    what the limit is counted per
 * @param capacity requests allowed per window (also the burst size)
 * @param window   time over which the capacity is refilled
 */
public record RateLimitRule(String name, Set<String> methods, List<String> paths, KeyBy keyBy, long capacity,
        Duration window) {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    public RateLimitRule {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        methods = Set.copyOf(methods);
        paths = List.copyOf(paths);
    }

    public static RateLimitRule of(String name, KeyBy keyBy, long capacity, Duration window, String... paths) {
        return new RateLimitRule(name, Set.of(), List.of(paths), keyBy, capacity, window);
    }

    public RateLimitRule onlyMethods(String... httpMethods) {
        return new RateLimitRule(name, Set.of(httpMethods), paths, keyBy, capacity, window);
    }

    boolean matches(HttpServletRequest request) {
        if (!methods.isEmpty() && !methods.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return paths.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }
}
