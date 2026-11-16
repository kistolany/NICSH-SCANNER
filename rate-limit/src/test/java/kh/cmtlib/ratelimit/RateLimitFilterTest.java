package kh.cmtlib.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RateLimitFilterTest {

    static GenericContainer<?> redis;
    static RedisClient client;
    static RedisRateLimiter limiter;

    @BeforeAll
    static void start() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        redis.start();
        client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        limiter = new RedisRateLimiter(client);
    }

    @AfterAll
    static void stop() {
        client.shutdown();
        redis.stop();
    }

    /** A rule with a name no other test uses, so tests cannot see each other's counters. */
    static String unique() {
        return "t-" + UUID.randomUUID();
    }

    static MockHttpServletResponse call(RateLimitFilter filter, String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(200);   // reached the application
        }
        return response;
    }

    @Test
    void allowsTheCapacityThenAnswers429WithRetryAfterAndAProblemBody() throws Exception {
        var rule = RateLimitRule.of(unique(), KeyBy.IP, 5, Duration.ofMinutes(1), "/login");
        var filter = new RateLimitFilter(limiter, List.of(rule), r -> null);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).as("request %d", i + 1).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call(filter, "POST", "/login", "10.0.0.1");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 15);   // 5/min = one token per 12 s
        assertThat(blocked.getContentType()).startsWith("application/problem+json");
        assertThat(blocked.getContentAsString())
                .contains("\"code\":\"RATE_LIMITED\"").contains("\"status\":429")
                .contains("\"type\":\"urn:cmt:error:RATE_LIMITED\"").contains("\"retryAfterSeconds\":");
    }

    @Test
    void everyIpHasItsOwnBucket() throws Exception {
        var rule = RateLimitRule.of(unique(), KeyBy.IP, 2, Duration.ofMinutes(1), "/login");
        var filter = new RateLimitFilter(limiter, List.of(rule), r -> null);

        call(filter, "POST", "/login", "10.0.0.1");
        call(filter, "POST", "/login", "10.0.0.1");
        assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(call(filter, "POST", "/login", "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void perUserRulesCountEachUserApart_andFallBackToTheIpWhenNobodyIsSignedIn() throws Exception {
        var rule = RateLimitRule.of(unique(), KeyBy.USER, 2, Duration.ofMinutes(1), "/api/**");
        AtomicReference<String> user = new AtomicReference<>("alice");
        var filter = new RateLimitFilter(limiter, List.of(rule), r -> user.get());

        // alice uses her 2, from two different IPs: still her bucket
        assertThat(call(filter, "GET", "/api/x", "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/x", "10.0.0.9").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/x", "10.0.0.1").getStatus()).isEqualTo(429);

        user.set("bob");
        assertThat(call(filter, "GET", "/api/x", "10.0.0.1").getStatus()).isEqualTo(200);   // bob is not alice

        user.set(null);   // not signed in: counted per IP
        assertThat(call(filter, "GET", "/api/x", "10.0.0.5").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/x", "10.0.0.5").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/x", "10.0.0.5").getStatus()).isEqualTo(429);
    }

    @Test
    void onlyMatchingMethodsAndPathsAreCounted() throws Exception {
        var rule = RateLimitRule.of(unique(), KeyBy.IP, 1, Duration.ofMinutes(1), "/login").onlyMethods("POST");
        var filter = new RateLimitFilter(limiter, List.of(rule), r -> null);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/login", "10.0.0.1").getStatus()).isEqualTo(200);        // wrong method
            assertThat(call(filter, "POST", "/somewhere-else", "10.0.0.1").getStatus()).isEqualTo(200);   // wrong path
        }
        assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(429);
    }

    @Test
    void aRequestIsCountedAgainstEveryRuleItMatches() throws Exception {
        var general = RateLimitRule.of(unique(), KeyBy.USER, 100, Duration.ofMinutes(1), "/api/**");
        var scan = RateLimitRule.of(unique(), KeyBy.USER, 3, Duration.ofMinutes(1), "/api/v1/app/attendance/scan");
        var filter = new RateLimitFilter(limiter, List.of(general, scan), r -> "carol");

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "POST", "/api/v1/app/attendance/scan", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        assertThat(call(filter, "POST", "/api/v1/app/attendance/scan", "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(call(filter, "GET", "/api/v1/app/me", "10.0.0.1").getStatus()).isEqualTo(200);   // other paths are fine
    }

    @Test
    void tokensComeBackOverTime() throws Exception {
        var rule = RateLimitRule.of(unique(), KeyBy.IP, 2, Duration.ofSeconds(1), "/login");   // one token per 0.5 s
        var filter = new RateLimitFilter(limiter, List.of(rule), r -> null);

        call(filter, "POST", "/login", "10.0.0.1");
        call(filter, "POST", "/login", "10.0.0.1");
        assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(429);

        Thread.sleep(1200);
        assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void whenRedisIsUnreachableRequestsAreLetThrough() throws Exception {
        RedisClient dead = RedisClient.create("redis://127.0.0.1:1");
        try {
            var rule = RateLimitRule.of(unique(), KeyBy.IP, 1, Duration.ofMinutes(1), "/login");
            var filter = new RateLimitFilter(new RedisRateLimiter(dead), List.of(rule), r -> null);
            for (int i = 0; i < 3; i++) {
                assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
            }
        } finally {
            dead.shutdown();
        }
    }

    @Test
    void redisGoingDownWhileRunningDoesNotBlockRequests() throws Exception {
        try (GenericContainer<?> own = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379)) {
            own.start();
            RedisClient ownClient = RedisClient.create("redis://" + own.getHost() + ":" + own.getMappedPort(6379));
            var rule = RateLimitRule.of(unique(), KeyBy.IP, 1, Duration.ofMinutes(1), "/login");
            var filter = new RateLimitFilter(new RedisRateLimiter(ownClient), List.of(rule), r -> null);
            assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
            assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(429);   // working

            own.stop();   // Redis dies

            long started = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                assertThat(call(filter, "POST", "/login", "10.0.0.1").getStatus()).isEqualTo(200);
            }
            // the first failing request waits at most the command timeout; the rest skip Redis during the back-off
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            ownClient.shutdown();
        }
    }

    @Test
    void parallelRequestsNeverLetMoreThroughThanTheCapacity() throws Exception {
        var rule = RateLimitRule.of(unique(), KeyBy.IP, 20, Duration.ofMinutes(1), "/login");
        var filter = new RateLimitFilter(limiter, List.of(rule), r -> null);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 100; i++) {
            results.add(pool.submit(() -> call(filter, "POST", "/login", "10.0.0.1").getStatus()));
        }
        long ok = 0;
        for (var f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isBetween(20L, 21L);   // 20 tokens (+ at most 1 refilled while the test ran)
    }
}
