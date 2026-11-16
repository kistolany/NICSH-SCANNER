package kh.cmt.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The API tests' clock: the real time in the school's zone, until a test fixes it ({@link #set}) to play a day
 * (check-in at 07:57, check-out at 15:30…). Always {@link #reset} afterwards: the application context is shared.
 */
public class TestClock extends Clock {

    private final ZoneId zone;
    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public TestClock(ZoneId zone) {
        this.zone = zone;
    }

    public void set(Instant at) {
        fixed.set(at);
    }

    public void reset() {
        fixed.set(null);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return other.equals(zone) ? this : Clock.system(other);
    }

    @Override
    public Instant instant() {
        Instant at = fixed.get();
        return at != null ? at : Instant.now();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(ZoneId.of("Asia/Phnom_Penh"));
        }
    }
}
