package kh.cmt.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kh.cmt.attendance.api.TodayCard.Phase;
import kh.cmt.attendance.api.TodayCard.State;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.schedule.api.DaySchedules.Kind;
import org.junit.jupiter.api.Test;

/** Step 7.9: every state of the Today card (SPEC §4.4 one shift, §4.5 two shifts), from today's schedule, records and clock. */
class TodayStatesTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    static final UUID PERSON = UUID.randomUUID();
    static final DaySchedules.Shift FULL = shift(1, "07:45", "17:00", false);
    static final DaySchedules.Shift MORNING = shift(1, "07:45", "11:30", false);
    static final DaySchedules.Shift AFTERNOON = shift(2, "13:30", "17:00", false);
    static final List<DaySchedules.Shift> ONE = List.of(FULL);
    static final List<DaySchedules.Shift> TWO = List.of(MORNING, AFTERNOON);

    @Test
    void oneShift() {
        assertCard(card("07:30", ONE), State.NOT_CHECKED_IN, 0, Phase.UPCOMING);                               // 1
        assertCard(card("10:00", ONE, in(FULL, "07:40", null)), State.WORKING, 0, Phase.WORKING);              // 2 on time
        TodayStates.Card late = card("10:00", ONE, in(FULL, "07:57", null));                                   // 3 late (minutes on the record)
        assertCard(late, State.WORKING, 0, Phase.WORKING);
        assertCard(card("17:30", ONE, in(FULL, "07:40", "17:10")), State.DONE, 0, Phase.DONE);                 // 4
        assertCard(card("15:40", ONE, in(FULL, "07:40", "15:30")), State.EARLY_OUT, 0, Phase.EARLY);           // 5
        assertCard(TodayStates.of(schedule(Kind.WORK, ONE), lines(ONE), at("07:50"), PP, true, false),
                State.OUTSIDE_AREA, 0, Phase.UPCOMING);                                                        // 6
        assertCard(TodayStates.of(schedule(Kind.WORK, ONE), lines(ONE), at("07:30"), PP, false, true),
                State.MISSING_CHECK_OUT, 0, Phase.UPCOMING);                                                   // 7
        assertCard(card("17:00", ONE), State.ABSENT, 0, Phase.MISSED);                                         // 8 the shift is over
        // the end-of-day job's ABSENT record says the same
        AttendanceRecord absent = AttendanceRecord.without(PERSON, TODAY, plan(FULL), AttendanceRecord.Status.ABSENT);
        assertCard(card("23:40", ONE, absent), State.ABSENT, 0, Phase.MISSED);
        // checked in and still not out after the shift: still at work until the end of the day
        assertCard(card("18:00", ONE, in(FULL, "07:40", null)), State.WORKING, 0, Phase.WORKING);
    }

    @Test
    void absentWinsOverAMissingCheckOut_andOutsideOverTheLastDay() {
        assertThat(TodayStates.of(schedule(Kind.WORK, ONE), lines(ONE), at("17:30"), PP, true, true).state()).isEqualTo(State.ABSENT);
        assertThat(TodayStates.of(schedule(Kind.WORK, ONE), lines(ONE), at("08:00"), PP, true, true).state()).isEqualTo(State.OUTSIDE_AREA);
        // once checked in, the last day's missing check-out no longer replaces the card
        assertThat(TodayStates.of(schedule(Kind.WORK, ONE), lines(ONE, in(FULL, "07:40", null)), at("08:00"), PP, false, true).state())
                .isEqualTo(State.WORKING);
    }

    @Test
    void noWorkToday() {
        assertThat(card("09:00", List.of(), Kind.HOLIDAY).state()).isEqualTo(State.HOLIDAY);                     // 11
        assertThat(card("09:00", List.of(shift(1, "07:45", "17:00", true)), Kind.LEAVE).state()).isEqualTo(State.ON_LEAVE);   // 10
        assertThat(card("09:00", List.of(), Kind.OFF).state()).isEqualTo(State.DAY_OFF);
        assertThat(card("09:00", List.of(), Kind.NO_SCHEDULE).state()).isEqualTo(State.DAY_OFF);
    }

    @Test
    void twoShifts() {
        assertCard(card("07:30", TWO), State.NOT_CHECKED_IN, 0, Phase.UPCOMING, Phase.UPCOMING);
        assertCard(card("09:00", TWO, in(MORNING, "07:40", null)), State.WORKING, 0, Phase.WORKING, Phase.UPCOMING);   // working shift 1
        assertCard(card("12:10", TWO, in(MORNING, "07:40", "11:32")), State.BREAK, 1, Phase.DONE, Phase.UPCOMING);     // break
        assertCard(card("12:10", TWO, in(MORNING, "07:40", "11:00")), State.BREAK, 1, Phase.EARLY, Phase.UPCOMING);
        assertCard(card("14:00", TWO, in(MORNING, "07:40", "11:32"), in(AFTERNOON, "13:25", null)),
                State.WORKING, 1, Phase.DONE, Phase.WORKING);                                                                 // working shift 2
        assertCard(card("17:10", TWO, in(MORNING, "07:40", "11:32"), in(AFTERNOON, "13:25", "17:05")),
                State.DONE, 1, Phase.DONE, Phase.DONE);                                                                       // 2 shifts completed
        assertCard(card("17:10", TWO, in(MORNING, "07:40", "11:32"), in(AFTERNOON, "13:25", "16:00")),
                State.DONE, 1, Phase.DONE, Phase.EARLY);
    }

    @Test
    void twoShifts_aMissedShift() {
        // shift 1 never scanned, shift 2 to come: the card waits for shift 2 (the app shows the "missed" banner)
        assertCard(card("12:00", TWO), State.NOT_CHECKED_IN, 1, Phase.MISSED, Phase.UPCOMING);
        // shift 1 checked in, never out, shift 2 checked in: shift 1 missed its check-out
        assertCard(card("14:00", TWO, in(MORNING, "07:40", null), in(AFTERNOON, "13:25", null)),
                State.WORKING, 1, Phase.MISSED, Phase.WORKING);
        // shift 1 done, shift 2 never scanned and over
        assertCard(card("17:30", TWO, in(MORNING, "07:40", "11:32")), State.MISSED, 1, Phase.DONE, Phase.MISSED);
        // both over without a scan
        assertCard(card("17:30", TWO), State.ABSENT, 1, Phase.MISSED, Phase.MISSED);
        // outside the area when checking in for shift 2
        assertCard(TodayStates.of(schedule(Kind.WORK, TWO), lines(TWO, in(MORNING, "07:40", "11:32")), at("13:20"), PP, true, false),
                State.OUTSIDE_AREA, 1, Phase.DONE, Phase.UPCOMING);
    }

    @Test
    void aShiftOnLeave_isLeftForTheOther() {
        List<DaySchedules.Shift> afternoonOff = List.of(MORNING, shift(2, "13:30", "17:00", true));
        assertCard(card("09:00", afternoonOff, in(MORNING, "07:40", null)), State.WORKING, 0, Phase.WORKING, Phase.LEAVE);
        assertCard(card("12:00", afternoonOff, in(MORNING, "07:40", "11:32")), State.DONE, 0, Phase.DONE, Phase.LEAVE);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertCard(TodayStates.Card card, State state, int current, Phase... phases) {
        assertThat(card.state()).isEqualTo(state);
        assertThat(card.current()).isEqualTo(current);
        assertThat(card.phases()).containsExactly(phases);
    }

    private static TodayStates.Card card(String now, List<DaySchedules.Shift> shifts, AttendanceRecord... records) {
        return card(now, shifts, Kind.WORK, records);
    }

    private static TodayStates.Card card(String now, List<DaySchedules.Shift> shifts, Kind kind, AttendanceRecord... records) {
        DaySchedules.ScheduledDay day = schedule(kind, shifts);
        return TodayStates.of(day, DayHistory.today(day, List.of(records)).lines(), at(now), PP, false, false);
    }

    private static List<DayHistory.Line> lines(List<DaySchedules.Shift> shifts, AttendanceRecord... records) {
        return DayHistory.today(schedule(Kind.WORK, shifts), List.of(records)).lines();
    }

    private static DaySchedules.ScheduledDay schedule(Kind kind, List<DaySchedules.Shift> shifts) {
        return new DaySchedules.ScheduledDay(TODAY, kind, "WEEKLY", "PERSON", false, null, null, null, null, shifts);
    }

    private static DaySchedules.Shift shift(int position, String start, String end, boolean onLeave) {
        return new DaySchedules.Shift(position, null, LocalTime.parse(start), LocalTime.parse(end), 0, 5, true, onLeave);
    }

    private static AttendanceRecord.Plan plan(DaySchedules.Shift s) {
        return new AttendanceRecord.Plan(s.position(), s.name(), s.start(), s.end(), s.graceMinutes(), s.earlyCheckOutNeedsReason());
    }

    private static AttendanceRecord in(DaySchedules.Shift s, String in, String out) {
        AttendanceRecord r = AttendanceRecord.checkedIn(PERSON, TODAY, plan(s), at(in), null, null, AttendanceRecord.Source.SCAN, PP);
        if (out != null) {
            r.checkOut(at(out), null, null, null, PP);
        }
        return r;
    }

    private static Instant at(String time) {
        return TODAY.atTime(LocalTime.parse(time)).atZone(PP).toInstant();
    }
}
