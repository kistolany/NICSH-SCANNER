package kh.cmt.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kh.cmt.attendance.application.DayHistory.ShiftStatus;
import kh.cmt.attendance.application.DayHistory.Status;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.schedule.api.DaySchedules.Kind;
import kh.cmt.shared.i18n.LocalizedName;
import org.junit.jupiter.api.Test;

/** Step 7.5: what one day of the history says, from its schedule and records. */
class DayHistoryTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final LocalDate YESTERDAY = TODAY.minusDays(1);
    static final UUID PERSON = UUID.randomUUID();
    static final DaySchedules.Shift FULL = shift(1, "07:45", "17:00", false);
    static final DaySchedules.Shift MORNING = shift(1, "07:45", "11:30", false);
    static final DaySchedules.Shift AFTERNOON = shift(2, "13:30", "17:00", false);

    @Test
    void aWorkedDay_onTimeLateEarly_andItsMinutes() {
        DayHistory.Day onTime = day(YESTERDAY, Kind.WORK, List.of(FULL), in(YESTERDAY, FULL, "07:40", "17:02"));
        assertThat(onTime.status()).isEqualTo(Status.ON_TIME);
        assertThat(onTime.workedMinutes()).isEqualTo(562);
        assertThat(onTime.lines()).extracting(DayHistory.Line::status).containsExactly(ShiftStatus.ON_TIME);

        DayHistory.Day late = day(YESTERDAY, Kind.WORK, List.of(FULL), in(YESTERDAY, FULL, "07:57", "17:00"));
        assertThat(late.status()).isEqualTo(Status.LATE);
        assertThat(late.lateMinutes()).isEqualTo(12);

        DayHistory.Day early = day(YESTERDAY, Kind.WORK, List.of(FULL), in(YESTERDAY, FULL, "07:40", "15:30"));
        assertThat(early.status()).isEqualTo(Status.EARLY);
        assertThat(early.earlyMinutes()).isEqualTo(90);
    }

    @Test
    void noScan_absentWhenPast_nothingYetToday() {
        DayHistory.Day absent = day(YESTERDAY, Kind.WORK, List.of(FULL));
        assertThat(absent.status()).isEqualTo(Status.ABSENT);
        assertThat(absent.lines()).extracting(DayHistory.Line::status).containsExactly(ShiftStatus.ABSENT);
        assertThat(DayHistory.of(TODAY, schedule(TODAY, Kind.WORK, List.of(FULL)), List.of())).isEmpty();
        // the end-of-day job's ABSENT record (7.8) says the same
        AttendanceRecord stored = AttendanceRecord.without(PERSON, YESTERDAY, plan(FULL), AttendanceRecord.Status.ABSENT);
        assertThat(day(YESTERDAY, Kind.WORK, List.of(FULL), stored).status()).isEqualTo(Status.ABSENT);
    }

    @Test
    void checkedInNotOut_missingOnceTheDayIsOver() {
        assertThat(day(YESTERDAY, Kind.WORK, List.of(FULL), in(YESTERDAY, FULL, "07:40", null)).status()).isEqualTo(Status.MISSING);
        DayHistory.Day working = day(TODAY, Kind.WORK, List.of(FULL), in(TODAY, FULL, "07:40", null));
        assertThat(working.status()).isEqualTo(Status.ON_TIME);   // still at work
        assertThat(working.workedMinutes()).isZero();
    }

    @Test
    void twoShifts_theWorstCounts_andAMissedShiftIsMissing() {
        DayHistory.Day lateMorning = day(YESTERDAY, Kind.WORK, List.of(MORNING, AFTERNOON), in(YESTERDAY, MORNING, "08:00", "11:30"),
                in(YESTERDAY, AFTERNOON, "13:25", "17:00"));
        assertThat(lateMorning.status()).isEqualTo(Status.LATE);
        assertThat(lateMorning.workedMinutes()).isEqualTo(210 + 215);
        assertThat(lateMorning.lines()).extracting(DayHistory.Line::status).containsExactly(ShiftStatus.LATE, ShiftStatus.ON_TIME);

        DayHistory.Day noAfternoon = day(YESTERDAY, Kind.WORK, List.of(MORNING, AFTERNOON), in(YESTERDAY, MORNING, "07:40", "11:30"));
        assertThat(noAfternoon.status()).isEqualTo(Status.MISSING);
        assertThat(noAfternoon.lines()).extracting(DayHistory.Line::status).containsExactly(ShiftStatus.ON_TIME, ShiftStatus.ABSENT);

        DayHistory.Day afternoonToCome = day(TODAY, Kind.WORK, List.of(MORNING, AFTERNOON), in(TODAY, MORNING, "07:40", "11:30"));
        assertThat(afternoonToCome.status()).isEqualTo(Status.ON_TIME);
        assertThat(afternoonToCome.lines()).extracting(DayHistory.Line::status).containsExactly(ShiftStatus.ON_TIME, ShiftStatus.NOT_YET);

        DayHistory.Day afternoonOnLeave = day(YESTERDAY, Kind.WORK, List.of(MORNING, shift(2, "13:30", "17:00", true)),
                in(YESTERDAY, MORNING, "07:40", "11:30"));
        assertThat(afternoonOnLeave.status()).isEqualTo(Status.ON_TIME);
        assertThat(afternoonOnLeave.lines()).extracting(DayHistory.Line::status).containsExactly(ShiftStatus.ON_TIME, ShiftStatus.LEAVE);
    }

    @Test
    void holidaysLeaveDaysOffAndTheFuture() {
        DaySchedules.ScheduledDay holiday = new DaySchedules.ScheduledDay(YESTERDAY, Kind.HOLIDAY, "HOLIDAY", null, false, null, null,
                new DaySchedules.Holiday(UUID.randomUUID(), new LocalizedName("ភ្ជុំបិណ្ឌ", "Pchum Ben", null), "NATIONAL"), null, List.of());
        assertThat(DayHistory.of(TODAY, holiday, List.of()).orElseThrow().status()).isEqualTo(Status.HOLIDAY);
        assertThat(day(YESTERDAY, Kind.LEAVE, List.of(shift(1, "07:45", "17:00", true))).status()).isEqualTo(Status.LEAVE);
        assertThat(DayHistory.of(TODAY, schedule(YESTERDAY, Kind.OFF, List.of()), List.of())).isEmpty();
        assertThat(DayHistory.of(TODAY, schedule(YESTERDAY, Kind.NO_SCHEDULE, List.of()), List.of())).isEmpty();
        assertThat(DayHistory.of(TODAY, schedule(TODAY.plusDays(1), Kind.WORK, List.of(FULL)), List.of())).isEmpty();
        // worked on a day that is off now (the schedule changed afterwards): the record still shows
        assertThat(day(YESTERDAY, Kind.OFF, List.of(), in(YESTERDAY, FULL, "07:40", "17:00")).status()).isEqualTo(Status.ON_TIME);
    }

    @Test
    void earlyReasonsAreSplitIntoCodeAndNote() {
        assertThat(ShiftViews.earlyReason("personal: Family")).isEqualTo(new HistoryViews.EarlyReason("personal", "Family"));
        assertThat(ShiftViews.earlyReason("sick")).isEqualTo(new HistoryViews.EarlyReason("sick", null));
        assertThat(ShiftViews.earlyReason(null)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static DayHistory.Day day(LocalDate date, Kind kind, List<DaySchedules.Shift> shifts, AttendanceRecord... records) {
        return DayHistory.of(TODAY, schedule(date, kind, shifts), List.of(records)).orElseThrow();
    }

    private static DaySchedules.ScheduledDay schedule(LocalDate date, Kind kind, List<DaySchedules.Shift> shifts) {
        return new DaySchedules.ScheduledDay(date, kind, "WEEKLY", "PERSON", false, null, null, null, null, shifts);
    }

    private static DaySchedules.Shift shift(int position, String start, String end, boolean onLeave) {
        return new DaySchedules.Shift(position, null, LocalTime.parse(start), LocalTime.parse(end), 0, 5, true, onLeave);
    }

    private static AttendanceRecord.Plan plan(DaySchedules.Shift s) {
        return new AttendanceRecord.Plan(s.position(), s.name(), s.start(), s.end(), s.graceMinutes(), s.earlyCheckOutNeedsReason());
    }

    private static AttendanceRecord in(LocalDate date, DaySchedules.Shift s, String in, String out) {
        AttendanceRecord r = AttendanceRecord.checkedIn(PERSON, date, plan(s), at(date, in), null, null, AttendanceRecord.Source.SCAN, PP);
        if (out != null) {
            r.checkOut(at(date, out), null, null, null, PP);
        }
        return r;
    }

    private static Instant at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(PP).toInstant();
    }
}
