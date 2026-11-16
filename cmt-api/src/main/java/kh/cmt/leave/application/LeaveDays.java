package kh.cmt.leave.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kh.cmt.schedule.api.DaySchedules;

/**
 * How many days a request takes (no database): every day of the range on which the person has shifts counts 1 — days
 * off, holidays and days without a schedule do not count; half a day counts 0.5 when that date has shifts. Unit-tested
 * ({@code LeaveDaysTest}).
 */
final class LeaveDays {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private LeaveDays() {
    }

    /** The days counted and the dates they are on. */
    record Count(BigDecimal days, List<LocalDate> dates) {

        boolean none() {
            return dates.isEmpty();
        }
    }

    static Count of(List<DaySchedules.ScheduledDay> schedule, boolean half) {
        List<LocalDate> dates = schedule.stream().filter(LeaveDays::working).map(DaySchedules.ScheduledDay::date).toList();
        BigDecimal days = half ? (dates.isEmpty() ? BigDecimal.ZERO : HALF) : BigDecimal.valueOf(dates.size());
        return new Count(days.setScale(1), dates);
    }

    /** A day with shifts to work (already-approved leave on it is a separate check: overlapping requests). */
    private static boolean working(DaySchedules.ScheduledDay d) {
        return (d.kind() == DaySchedules.Kind.WORK || d.kind() == DaySchedules.Kind.LEAVE) && !d.shifts().isEmpty();
    }
}
