package kh.cmt.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import kh.cmt.bff.LoginRulesWebFilter.Rules;
import org.junit.jupiter.api.Test;

/** The rule checks themselves (the request side is in {@link BffIntegrationTest}). */
class LoginRulesWebFilterTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Phnom_Penh");
    // Wednesday 2026-10-21 08:30 school time
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T01:30:00Z"), ZONE);

    @Test
    void addressesAndRanges() {
        assertThat(Rules.matches("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(Rules.matches("192.168.1.0/24", "192.168.1.77")).isTrue();
        assertThat(Rules.matches("192.168.1.0/24", "192.168.2.1")).isFalse();
        assertThat(Rules.matches("10.0.0.0/8", "10.255.0.1")).isTrue();
        assertThat(Rules.matches("::1", "0:0:0:0:0:0:0:1")).isTrue();
        assertThat(Rules.matches("2001:db8::/32", "2001:db8:1::5")).isTrue();
        assertThat(Rules.matches("10.0.0.0/8", "::1")).isFalse();             // IPv4 range, IPv6 caller
        assertThat(Rules.matches("school.example.com", "10.0.0.1")).isFalse(); // never a DNS lookup
        assertThat(Rules.matches("10.0.0.0/40", "10.0.0.1")).isFalse();
    }

    @Test
    void hoursDaysAndAddresses() {
        assertThat(rules(LocalTime.of(7, 0), LocalTime.of(17, 0), null, null).refusal(CLOCK, "10.0.0.1")).isEmpty();
        assertThat(rules(LocalTime.of(9, 0), LocalTime.of(17, 0), null, null).refusal(CLOCK, "10.0.0.1")).contains("OUTSIDE_HOURS");
        assertThat(rules(LocalTime.of(22, 0), LocalTime.of(9, 0), null, null).refusal(CLOCK, "10.0.0.1")).isEmpty();   // past midnight
        assertThat(rules(null, null, List.of(1, 2), null).refusal(CLOCK, "10.0.0.1")).contains("DAY_NOT_ALLOWED");
        assertThat(rules(null, null, List.of(3), null).refusal(CLOCK, "10.0.0.1")).isEmpty();
        assertThat(rules(null, null, null, List.of("10.0.0.0/8")).refusal(CLOCK, "11.0.0.1")).contains("IP_NOT_ALLOWED");
        assertThat(rules(null, null, null, List.of("10.0.0.0/8")).refusal(CLOCK, null)).contains("IP_NOT_ALLOWED");
    }

    private static Rules rules(LocalTime from, LocalTime to, List<Integer> days, List<String> ips) {
        return new Rules(null, from, to, days, ips, ZONE);
    }
}
