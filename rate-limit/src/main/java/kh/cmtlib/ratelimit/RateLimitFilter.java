package kh.cmtlib.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link RateLimitRule}s. A request is counted against every rule it matches; the first rule that is
 * exhausted answers {@code 429 Too Many Requests} with {@code Retry-After} and a problem body
 * {@code {type, title, status, code: RATE_LIMITED, retryAfterSeconds}}, the same shape as every other error.
 * <p>
 * Do <b>not</b> expose this as a Spring bean of type Filter (Boot would register it twice): register it
 * explicitly where it belongs in the chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RedisRateLimiter limiter;
    private final List<RateLimitRule> rules;
    private final Function<HttpServletRequest, String> userResolver;

    /**
     * @param userResolver returns the signed-in user's id, or null when nobody is signed in (such requests
     *                     are then counted per IP, also for rules that are per user)
     */
    public RateLimitFilter(RedisRateLimiter limiter, List<RateLimitRule> rules,
            Function<HttpServletRequest, String> userResolver) {
        this.limiter = limiter;
        this.rules = List.copyOf(rules);
        this.userResolver = userResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (RateLimitRule rule : rules) {
            if (!rule.matches(request)) {
                continue;
            }
            RedisRateLimiter.Decision decision = limiter.tryConsume(rule, keyFor(rule, request));
            if (!decision.allowed()) {
                tooManyRequests(request, response, rule, decision);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private String keyFor(RateLimitRule rule, HttpServletRequest request) {
        if (rule.keyBy() == KeyBy.USER) {
            String user = userResolver.apply(request);
            if (user != null) {
                return "user:" + user;
            }
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static void tooManyRequests(HttpServletRequest request, HttpServletResponse response, RateLimitRule rule,
            RedisRateLimiter.Decision decision) throws IOException {
        long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
        log.warn("Rate limit '{}' exceeded by {} on {} {}", rule.name(), request.getRemoteAddr(),
                request.getMethod(), request.getRequestURI());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:cmt:error:RATE_LIMITED\",\"title\":\"Too Many Requests\","
                + "\"status\":429,\"code\":\"RATE_LIMITED\",\"retryAfterSeconds\":" + seconds + "}");
    }
}
