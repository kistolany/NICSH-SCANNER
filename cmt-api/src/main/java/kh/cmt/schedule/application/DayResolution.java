package kh.cmt.schedule.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kh.cmt.schedule.api.DaySchedules.Holiday;
import kh.cmt.schedule.api.DaySchedules.Kind;
import kh.cmt.schedule.api.DaySchedules.Leave;
import kh.cmt.schedule.api.DaySchedules.ScheduledDay;
import kh.cmt.schedule.api.DaySchedules.Shift;
import kh.cmt.schedule.api.DaySchedules.Template;
import kh.cmt.schedule.api.PersonLeave.LeaveDay;
import kh.cmt.schedule.api.ShiftTemplateQueries.TemplateInfo;
import kh.cmt.schedule.api.SpecialDays.ChangeDay;
import kh.cmt.schedule.api.SpecialDays.HolidayDay;
import kh.cmt.schedule.api.WeeklySchedules.Week;

/**
 * The rule of a person's day, with no database: holiday → date change → weekly schedule, then leave on the shifts.
 * Kept apart from {@link ScheduleResolver} so every case can be unit-tested.
 */
final class DayResolution {

    private DayResolution() {
    }

    static ScheduledDay resolve(LocalDate date, Optional<HolidayDay> holiday, Optional<LeaveDay> leave, Optional<ChangeDay> change,
            Optional<Week> week, Function<UUID, Optional<TemplateInfo>> templates) {
        if (holiday.isPresent()) {
            HolidayDay h = holiday.get();
            return new ScheduledDay(date, Kind.HOLIDAY, "HOLIDAY", null, false, null, null,
                    new Holiday(h.id(), h.name(), h.kind()), null, List.of());
        }
        String source;
        String level;
        String label = null;
        UUID templateId;
        if (change.isPresent()) {
            source = "DATE_CHANGE";
            level = change.get().source();
            label = change.get().label();
            templateId = change.get().templateId();
        } else if (week.isPresent()) {
            source = "WEEKLY";
            level = week.get().source();
            templateId = week.get().templates().get(date.getDayOfWeek().getValue());
        } else {
            return new ScheduledDay(date, Kind.NO_SCHEDULE, null, null, false, null, null, null, null, List.of());
        }
        boolean changed = change.isPresent();
        Optional<TemplateInfo> template = templateId == null ? Optional.empty() : templates.apply(templateId);
        if (template.isEmpty() || template.get().shifts().isEmpty()) {
            return new ScheduledDay(date, Kind.OFF, source, level, changed, label, null, null, null, List.of());   // leave on a day off: nothing to miss
        }
        TemplateInfo t = template.get();
        String half = leave.map(LeaveDay::half).orElse(null);
        boolean split = half != null && t.shifts().size() == 1;   // a half day of a one-shift day: the other half is worked
        boolean wholeDay = leave.map(LeaveDay::wholeDay).orElse(false);
        Set<Integer> away = leave.map(l -> half == null ? l.shiftPositions() : Set.of(half.equals("AM") ? 1 : 2)).orElse(Set.of());
        List<Shift> shifts = t.shifts().stream().map(s -> {
            LocalTime start = s.start();
            LocalTime end = s.end();
            if (split) {
                LocalTime middle = start.plusMinutes(Duration.between(start, end).toMinutes() / 2);
                start = half.equals("AM") ? middle : start;
                end = half.equals("AM") ? end : middle;
            }
            return new Shift(s.position(), s.name(), start, end, Duration.between(start, end).toMinutes(), t.graceMinutes(),
                    t.earlyCheckOutNeedsReason(), !split && (wholeDay || away.contains(s.position())), t.checkInOpensMinutes());
        }).toList();
        boolean allAway = shifts.stream().allMatch(Shift::onLeave);
        Leave onLeave = leave.filter(l -> split || shifts.stream().anyMatch(Shift::onLeave))
                .map(l -> new Leave(l.id(), l.type(), allAway, half)).orElse(null);
        return new ScheduledDay(date, allAway ? Kind.LEAVE : Kind.WORK, source, level, changed, label,
                new Template(t.id(), t.name(), t.color()), null, onLeave, shifts);
    }
}
