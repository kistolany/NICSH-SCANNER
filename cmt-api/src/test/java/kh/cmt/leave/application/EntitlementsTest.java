package kh.cmt.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The school's entitlement rule (calendar year, pro-rata by months in the start year, rounded down). */
class EntitlementsTest {

    private static String of(double perYear, boolean halfDays, LocalDate start, int year) {
        return Entitlements.of(BigDecimal.valueOf(perYear), halfDays, start, year).toPlainString();
    }

    @Test
    void theWholeYear_withoutAStartDate_orWhenStartedBefore() {
        assertThat(of(12, true, null, 2026)).isEqualTo("12.0");
        assertThat(of(12, true, LocalDate.of(2025, 12, 31), 2026)).isEqualTo("12.0");
        assertThat(of(2.5, true, LocalDate.of(2020, 1, 1), 2026)).isEqualTo("2.5");
    }

    @Test
    void nothing_beforeTheStartYear() {
        assertThat(of(12, true, LocalDate.of(2027, 1, 5), 2026)).isEqualTo("0.0");
    }

    @Test
    void theStartYear_byTheMonthsLeft_countingTheStartMonth() {
        assertThat(of(12, true, LocalDate.of(2026, 7, 1), 2026)).isEqualTo("6.0");    // the design's example
        assertThat(of(12, true, LocalDate.of(2026, 7, 20), 2026)).isEqualTo("6.0");   // the start month counts whole
        assertThat(of(12, true, LocalDate.of(2026, 1, 1), 2026)).isEqualTo("12.0");
        assertThat(of(12, true, LocalDate.of(2026, 12, 31), 2026)).isEqualTo("1.0");
    }

    @Test
    void roundedDown_toHalfDays_orWholeDays() {
        assertThat(of(10, true, LocalDate.of(2026, 8, 10), 2026)).isEqualTo("4.0");   // 4.17
        assertThat(of(3, true, LocalDate.of(2026, 7, 1), 2026)).isEqualTo("1.5");
        assertThat(of(10, true, LocalDate.of(2026, 6, 1), 2026)).isEqualTo("5.5");    // 5.83
        assertThat(of(90, false, LocalDate.of(2026, 8, 10), 2026)).isEqualTo("37.0"); // 37.5, whole days only
        assertThat(of(90, true, LocalDate.of(2026, 8, 10), 2026)).isEqualTo("37.5");
    }
}
