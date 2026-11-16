package kh.cmt.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import kh.cmt.schedule.api.DaySchedules.Kind;
import kh.cmt.schedule.api.DaySchedules.ScheduledDay;
import kh.cmt.schedule.api.DaySchedules.Shift;
import org.junit.jupiter.api.Test;

/** Step 9.2: a request takes the days on which the person has shifts. */
class LeaveDaysTest {

    static final LocalDate MON = LocalDate.of(2026, 11, 2);

    static ScheduledDay day(LocalDate d, Kind kind) {
        List<Shift> shifts = kind == Kind.WORK || kind == Kind.LEAVE
                ? List.of(new Shift(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0), 555, 5, true, kind == Kind.LEAVE)) : List.of();
        return new ScheduledDay(d, kind, null, null, false, null, null, null, null, shifts);
    }

    @Test
    void workingDaysCount_daysOffHolidaysAndNoScheduleDoNot() {
        List<ScheduledDay> week = List.of(day(MON, Kind.WORK), day(MON.plusDays(1), Kind.HOLIDAY), day(MON.plusDays(2), Kind.WORK),
                day(MON.plusDays(3), Kind.WORK), day(MON.plusDays(4), Kind.NO_SCHEDULE), day(MON.plusDays(5), Kind.OFF), day(MON.plusDays(6), Kind.OFF));
        LeaveDays.Count c = LeaveDays.of(week, false);
        assertThat(c.days().toPlainString()).isEqualTo("3.0");
        assertThat(c.dates()).containsExactly(MON, MON.plusDays(2), MON.plusDays(3));
    }

    @Test
    void halfADay_isHalf_onAWorkingDayOnly() {
        assertThat(LeaveDays.of(List.of(day(MON, Kind.WORK)), true).days().toPlainString()).isEqualTo("0.5");
        LeaveDays.Count sunday = LeaveDays.of(List.of(day(MON.plusDays(6), Kind.OFF)), true);
        assertThat(sunday.none()).isTrue();
        assertThat(sunday.days().toPlainString()).isEqualTo("0.0");
        assertThat(LeaveDays.of(List.of(day(MON, Kind.OFF), day(MON.plusDays(1), Kind.HOLIDAY)), false).none()).isTrue();
    }
}
