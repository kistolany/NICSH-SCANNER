package kh.cmt.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.schedule.api.DaySchedules.Kind;
import kh.cmt.schedule.api.DaySchedules.ScheduledDay;
import kh.cmt.schedule.api.PersonLeave.LeaveDay;
import kh.cmt.schedule.api.ShiftTemplateQueries.TemplateInfo;
import kh.cmt.schedule.api.SpecialDays.ChangeDay;
import kh.cmt.schedule.api.SpecialDays.HolidayDay;
import kh.cmt.schedule.api.WeeklySchedules.Week;
import kh.cmt.shared.i18n.LocalizedName;
import org.junit.jupiter.api.Test;

/** Step 5.4 "done when": the day rule for a normal day, two shifts, Saturday, Sunday, an exam day, a holiday, leave. */
class DayResolutionTest {

    static final UUID SPLIT = UUID.randomUUID();
    static final UUID SATURDAY = UUID.randomUUID();
    static final UUID FULL = UUID.randomUUID();
    static final UUID EXAM = UUID.randomUUID();
    static final Map<UUID, TemplateInfo> TEMPLATES = Map.of(
            SPLIT, new TemplateInfo(SPLIT, "Split day", "#2563EB", 5, true, true, List.of(
                    new TemplateInfo.Shift(1, "Morning", LocalTime.of(7, 45), LocalTime.of(11, 30)),
                    new TemplateInfo.Shift(2, "Afternoon", LocalTime.of(13, 30), LocalTime.of(17, 0)))),
            SATURDAY, new TemplateInfo(SATURDAY, "Saturday morning", "#16A34A", 10, false, true, List.of(
                    new TemplateInfo.Shift(1, null, LocalTime.of(7, 30), LocalTime.of(11, 30)))),
            FULL, new TemplateInfo(FULL, "Full day", "#EA580C", 5, true, true, List.of(
                    new TemplateInfo.Shift(1, null, LocalTime.of(7, 45), LocalTime.of(17, 0)))),
            EXAM, new TemplateInfo(EXAM, "Exam day", "#9333EA", 0, true, true, List.of(
                    new TemplateInfo.Shift(1, null, LocalTime.of(7, 0), LocalTime.of(12, 0)))));

    /** Monday – Friday split day, Saturday morning, Sunday off (the design's school week), set for everyone. */
    static final Week WEEK = new Week("EVERYONE", LocalDate.of(2026, 9, 1),
            Map.of(1, SPLIT, 2, SPLIT, 3, SPLIT, 4, SPLIT, 5, SPLIT, 6, SATURDAY));

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    static ScheduledDay day(LocalDate date, Optional<HolidayDay> holiday, Optional<LeaveDay> leave, Optional<ChangeDay> change,
            Optional<Week> week) {
        return DayResolution.resolve(date, holiday, leave, change, week, id -> Optional.ofNullable(TEMPLATES.get(id)));
    }

    static ScheduledDay plain(LocalDate date) {
        return day(date, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(WEEK));
    }

    @Test
    void aNormalDayWithTwoShifts() {
        ScheduledDay d = plain(MONDAY);
        assertThat(d.kind()).isEqualTo(Kind.WORK);
        assertThat(d.source()).isEqualTo("WEEKLY");
        assertThat(d.level()).isEqualTo("EVERYONE");
        assertThat(d.changed()).isFalse();
        assertThat(d.template().name()).isEqualTo("Split day");
        assertThat(d.shifts()).hasSize(2);
        assertThat(d.shifts().get(0)).satisfies(s -> {
            assertThat(s.position()).isEqualTo(1);
            assertThat(s.name()).isEqualTo("Morning");
            assertThat(s.start()).isEqualTo(LocalTime.of(7, 45));
            assertThat(s.end()).isEqualTo(LocalTime.of(11, 30));
            assertThat(s.minutes()).isEqualTo(225);
            assertThat(s.graceMinutes()).isEqualTo(5);
            assertThat(s.earlyCheckOutNeedsReason()).isTrue();
            assertThat(s.onLeave()).isFalse();
        });
        assertThat(d.shifts().get(1).name()).isEqualTo("Afternoon");
        assertThat(d.shifts().get(1).minutes()).isEqualTo(210);
    }

    @Test
    void saturdayHasOneShift_sundayIsOff() {
        ScheduledDay saturday = plain(MONDAY.plusDays(5));
        assertThat(saturday.kind()).isEqualTo(Kind.WORK);
        assertThat(saturday.shifts()).hasSize(1);
        assertThat(saturday.shifts().get(0).graceMinutes()).isEqualTo(10);
        assertThat(saturday.shifts().get(0).earlyCheckOutNeedsReason()).isFalse();

        ScheduledDay sunday = plain(MONDAY.plusDays(6));
        assertThat(sunday.kind()).isEqualTo(Kind.OFF);
        assertThat(sunday.source()).isEqualTo("WEEKLY");
        assertThat(sunday.shifts()).isEmpty();
        assertThat(sunday.template()).isNull();
    }

    @Test
    void anExamDayChangeReplacesTheWeek_andADateChangeCanMakeADayOff() {
        ScheduledDay exam = day(MONDAY.plusDays(2), Optional.empty(), Optional.empty(),
                Optional.of(new ChangeDay(UUID.randomUUID(), "DEPARTMENT", EXAM, "Exam day")), Optional.of(WEEK));
        assertThat(exam.kind()).isEqualTo(Kind.WORK);
        assertThat(exam.source()).isEqualTo("DATE_CHANGE");
        assertThat(exam.level()).isEqualTo("DEPARTMENT");
        assertThat(exam.changed()).isTrue();
        assertThat(exam.label()).isEqualTo("Exam day");
        assertThat(exam.shifts()).singleElement().satisfies(s -> {
            assertThat(s.start()).isEqualTo(LocalTime.of(7, 0));
            assertThat(s.graceMinutes()).isZero();
        });

        ScheduledDay off = day(MONDAY, Optional.empty(), Optional.empty(),
                Optional.of(new ChangeDay(UUID.randomUUID(), "EVERYONE", null, "Staff training away")), Optional.of(WEEK));
        assertThat(off.kind()).isEqualTo(Kind.OFF);
        assertThat(off.changed()).isTrue();

        // a date change works even where no weekly schedule was set
        ScheduledDay noWeek = day(MONDAY, Optional.empty(), Optional.empty(),
                Optional.of(new ChangeDay(UUID.randomUUID(), "PERSON", FULL, null)), Optional.empty());
        assertThat(noWeek.kind()).isEqualTo(Kind.WORK);
        assertThat(noWeek.level()).isEqualTo("PERSON");
    }

    @Test
    void aHolidayWinsOverEverything() {
        HolidayDay water = new HolidayDay(UUID.randomUUID(), new LocalizedName("បុណ្យអុំទូក", "Water Festival", null), "NATIONAL");
        ScheduledDay d = day(MONDAY, Optional.of(water),
                Optional.of(new LeaveDay(UUID.randomUUID(), "ANNUAL", true, Set.of())),
                Optional.of(new ChangeDay(UUID.randomUUID(), "EVERYONE", EXAM, "Exam day")), Optional.of(WEEK));
        assertThat(d.kind()).isEqualTo(Kind.HOLIDAY);
        assertThat(d.holiday().name().en()).isEqualTo("Water Festival");
        assertThat(d.holiday().kind()).isEqualTo("NATIONAL");
        assertThat(d.shifts()).isEmpty();
        assertThat(d.leave()).isNull();   // no leave is used on a holiday
        assertThat(d.changed()).isFalse();
    }

    @Test
    void onLeave_wholeDayOrOneShift() {
        UUID leaveId = UUID.randomUUID();
        ScheduledDay whole = day(MONDAY, Optional.empty(), Optional.of(new LeaveDay(leaveId, "SICK", true, Set.of())), Optional.empty(),
                Optional.of(WEEK));
        assertThat(whole.kind()).isEqualTo(Kind.LEAVE);
        assertThat(whole.leave().wholeDay()).isTrue();
        assertThat(whole.leave().type()).isEqualTo("SICK");
        assertThat(whole.shifts()).hasSize(2).allMatch(s -> s.onLeave());   // kept: reports count the hours

        ScheduledDay morning = day(MONDAY, Optional.empty(), Optional.of(new LeaveDay(leaveId, "PERSONAL", false, Set.of(1))),
                Optional.empty(), Optional.of(WEEK));
        assertThat(morning.kind()).isEqualTo(Kind.WORK);   // the afternoon is still worked
        assertThat(morning.leave().wholeDay()).isFalse();
        assertThat(morning.shifts().get(0).onLeave()).isTrue();
        assertThat(morning.shifts().get(1).onLeave()).isFalse();

        // leave on a day off changes nothing
        ScheduledDay sunday = day(MONDAY.plusDays(6), Optional.empty(), Optional.of(new LeaveDay(leaveId, "ANNUAL", true, Set.of())),
                Optional.empty(), Optional.of(WEEK));
        assertThat(sunday.kind()).isEqualTo(Kind.OFF);
        assertThat(sunday.leave()).isNull();
    }

    /** Step 9.2: half a day — on a two-shift day the shift, on a one-shift day the other half of the shift is worked. */
    @Test
    void halfADay_aShiftOfTwo_orHalfOfTheOnlyShift() {
        UUID leaveId = UUID.randomUUID();
        ScheduledDay afternoon = day(MONDAY, Optional.empty(), Optional.of(new LeaveDay(leaveId, "annual", false, Set.of(), "PM")),
                Optional.empty(), Optional.of(WEEK));
        assertThat(afternoon.kind()).isEqualTo(Kind.WORK);
        assertThat(afternoon.leave().half()).isEqualTo("PM");
        assertThat(afternoon.shifts()).extracting(s -> s.onLeave()).containsExactly(false, true);

        Week fullDays = new Week("PERSON", LocalDate.of(2026, 9, 1), Map.of(1, FULL));
        ScheduledDay morning = day(MONDAY, Optional.empty(), Optional.of(new LeaveDay(leaveId, "annual", false, Set.of(), "AM")),
                Optional.empty(), Optional.of(fullDays));
        assertThat(morning.kind()).isEqualTo(Kind.WORK);
        assertThat(morning.leave().half()).isEqualTo("AM");
        assertThat(morning.leave().wholeDay()).isFalse();
        assertThat(morning.shifts()).singleElement().satisfies(s -> {   // 07:45 – 17:00 → the afternoon 12:22 – 17:00
            assertThat(s.onLeave()).isFalse();
            assertThat(s.start()).isEqualTo(LocalTime.of(12, 22));
            assertThat(s.end()).isEqualTo(LocalTime.of(17, 0));
            assertThat(s.minutes()).isEqualTo(278);
        });
        ScheduledDay pm = day(MONDAY, Optional.empty(), Optional.of(new LeaveDay(leaveId, "annual", false, Set.of(), "PM")),
                Optional.empty(), Optional.of(fullDays));
        assertThat(pm.shifts().get(0).start()).isEqualTo(LocalTime.of(7, 45));
        assertThat(pm.shifts().get(0).end()).isEqualTo(LocalTime.of(12, 22));
        assertThat(plain(MONDAY).leave()).isNull();
    }

    @Test
    void nobodySetASchedule() {
        ScheduledDay d = day(MONDAY, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(d.kind()).isEqualTo(Kind.NO_SCHEDULE);
        assertThat(d.source()).isNull();
        assertThat(d.shifts()).isEmpty();
    }
}
