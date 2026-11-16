package kh.cmt.leave.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The days of a leave type a person is entitled to in a calendar year (the school's rules, 2026-10-26), no database:
 * the type's days per year; in the year the person started, pro-rata by the months left counting the start month
 * (starting 1 or 20 July = 6 months = half), rounded down to half days (whole days for a type taken by whole days);
 * nothing in a year before the start; no start date = the whole year. Unused days do not carry over.
 */
final class Entitlements {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private Entitlements() {
    }

    static BigDecimal of(BigDecimal daysPerYear, boolean halfDays, LocalDate startDate, int year) {
        if (startDate == null || startDate.getYear() < year) {
            return daysPerYear.setScale(1, RoundingMode.UNNECESSARY);
        }
        if (startDate.getYear() > year) {
            return BigDecimal.ZERO.setScale(1);
        }
        int months = 13 - startDate.getMonthValue();
        BigDecimal exact = daysPerYear.multiply(BigDecimal.valueOf(months)).divide(TWELVE, 4, RoundingMode.DOWN);
        BigDecimal rounded = halfDays
                ? exact.multiply(TWO).setScale(0, RoundingMode.DOWN).divide(TWO, 1, RoundingMode.UNNECESSARY)
                : exact.setScale(0, RoundingMode.DOWN).setScale(1);
        return rounded;
    }
}
