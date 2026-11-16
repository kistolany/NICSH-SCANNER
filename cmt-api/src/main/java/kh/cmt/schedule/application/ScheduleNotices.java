package kh.cmt.schedule.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.people.api.PeopleQueries.PersonPlace;
import kh.cmt.schedule.api.ScheduleChanged;
import kh.cmt.schedule.domain.DateChange;
import kh.cmt.schedule.domain.Holiday;
import kh.cmt.schedule.domain.ShiftTemplate;
import kh.cmt.schedule.domain.WeekResolution;
import kh.cmt.schedule.domain.WeeklySchedule;
import kh.cmt.schedule.domain.WeeklySchedule.Target;
import kh.cmt.schedule.infrastructure.DateChangeRepository;
import kh.cmt.schedule.infrastructure.HolidayRepository;
import kh.cmt.schedule.infrastructure.ShiftTemplateRepository;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.security.ScopeFilter;
import org.springframework.stereotype.Component;

/**
 * Step 5.5: works out who a schedule change really reaches and writes a {@link ScheduleChanged} event to the outbox, in
 * the transaction of the change. Only active people, only days of today or later, and only when their day or week
 * actually changes: a person with a more specific date change on that day, or a holiday, is left out, and so is
 * someone whose week stays the same after a weekly save.
 */
@Component
class ScheduleNotices {

    private final Outbox outbox;
    private final PeopleQueries people;
    private final DateChangeRepository changes;
    private final HolidayRepository holidays;
    private final ShiftTemplateRepository templates;
    private final SchoolContext schoolContext;
    private final Clock clock;

    ScheduleNotices(Outbox outbox, PeopleQueries people, DateChangeRepository changes, HolidayRepository holidays,
            ShiftTemplateRepository templates, SchoolContext schoolContext, Clock clock) {
        this.outbox = outbox;
        this.people = people;
        this.changes = changes;
        this.holidays = holidays;
        this.templates = templates;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ date changes

    /** Date changes of this target were set (new or different) on {@code dates}; call after they are flushed. */
    void dateChangesSaved(Target target, UUID targetId, Collection<LocalDate> dates, UUID templateId, String label) {
        Map<List<LocalDate>, List<UUID>> byDates = whoseDayIsThisTarget(target, targetId, dates);
        if (byDates.isEmpty()) {
            return;
        }
        Optional<ShiftTemplate> template = templateId == null ? Optional.empty() : templates.findById(templateId);
        String name = template.map(ShiftTemplate::getName).orElse(null);
        List<String> times = template.map(ScheduleNotices::times).orElse(List.of());
        byDates.forEach((days, who) -> outbox.add(ScheduleChanged.TYPE,
                ScheduleChanged.days(who, days, label, name, times, templateId == null)));
    }

    /** A date change is about to be removed (call before deleting it): the people it applies to are told. */
    void dateChangeRemoving(DateChange change) {
        whoseDayIsThisTarget(change.getTargetType(), change.targetId(), List.of(change.getDate()))
                .forEach((days, who) -> outbox.add(ScheduleChanged.TYPE, ScheduleChanged.daysRemoved(who, days)));
    }

    /**
     * For each person of the target: the dates on which this target's date change is the one that applies to them (no
     * more specific one, no holiday), grouped by the same list of dates.
     */
    private Map<List<LocalDate>, List<UUID>> whoseDayIsThisTarget(Target target, UUID targetId, Collection<LocalDate> dates) {
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> days = dates.stream().filter(d -> !d.isBefore(today)).distinct().sorted().toList();
        Map<List<LocalDate>, List<UUID>> out = new LinkedHashMap<>();
        if (days.isEmpty()) {
            return out;
        }
        UUID school = schoolContext.currentSchoolId();
        List<DateChange> onDays = ScopeFilter.withoutScope(() -> changes.findBySchoolIdAndDateIn(school, days));
        List<Holiday> hols = holidays.overlapping(school, days.get(0), days.get(days.size() - 1));
        for (PersonPlace p : reached(target, targetId)) {
            List<LocalDate> his = days.stream().filter(d -> {
                Optional<DateChange> wins = onDays.stream().filter(c -> c.getDate().equals(d) && c.reaches(p.id(), p.departmentId()))
                        .max(Comparator.comparingInt(c -> c.getTargetType().ordinal()));
                return wins.map(c -> c.getTargetType() == target && Objects.equals(c.targetId(), targetId)).orElse(false)
                        && hols.stream().noneMatch(h -> h.covers(d) && h.reaches(p.departmentId()));
            }).toList();
            if (!his.isEmpty()) {
                out.computeIfAbsent(his, k -> new ArrayList<>()).add(p.id());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ weekly schedules

    /** What each person of the target works on each weekday from {@code day}, before a weekly save. */
    Map<UUID, Map<Integer, UUID>> weeks(Collection<WeeklySchedule> all, Target target, UUID targetId, LocalDate day) {
        Map<UUID, Map<Integer, UUID>> out = new HashMap<>();
        for (PersonPlace p : reached(target, targetId)) {
            out.put(p.id(), WeekResolution.forPerson(all, p.id(), p.departmentId(), day).map(ScheduleNotices::week).orElse(Map.of()));
        }
        return out;
    }

    /** After a weekly save from {@code from}: tells those of the target whose week is different now. */
    void weekSaved(Map<UUID, Map<Integer, UUID>> before, Collection<WeeklySchedule> after, Target target, UUID targetId, LocalDate from) {
        List<UUID> changed = new ArrayList<>();
        weeks(after, target, targetId, from).forEach((person, week) -> {
            if (!week.equals(before.getOrDefault(person, Map.of()))) {
                changed.add(person);
            }
        });
        if (!changed.isEmpty()) {
            outbox.add(ScheduleChanged.TYPE, ScheduleChanged.week(changed, from));
        }
    }

    private static Map<Integer, UUID> week(WeeklySchedule v) {
        Map<Integer, UUID> week = new HashMap<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            UUID t = v.templateOn(weekday);
            if (t != null) {
                week.put(weekday, t);
            }
        }
        return week;
    }

    // ------------------------------------------------------------------ holidays

    void holidayAdded(Holiday h) {
        holiday(ScheduleChanged.Kind.HOLIDAY_ADDED, h, ids(reachedBy(h.getDepartmentId())));
    }

    /** {@code departmentBefore}: whom it reached before the change (they are told too). */
    void holidayChanged(Holiday h, UUID departmentBefore) {
        Set<UUID> who = new LinkedHashSet<>(ids(reachedBy(departmentBefore)));
        who.addAll(ids(reachedBy(h.getDepartmentId())));
        holiday(ScheduleChanged.Kind.HOLIDAY_CHANGED, h, List.copyOf(who));
    }

    void holidayRemoving(Holiday h) {
        holiday(ScheduleChanged.Kind.HOLIDAY_REMOVED, h, ids(reachedBy(h.getDepartmentId())));
    }

    private void holiday(ScheduleChanged.Kind kind, Holiday h, List<UUID> who) {
        if (!who.isEmpty() && !h.getEndDate().isBefore(LocalDate.now(clock))) {
            outbox.add(ScheduleChanged.TYPE, ScheduleChanged.holiday(kind, who, h.getName(), h.getStartDate(), h.getEndDate()));
        }
    }

    // ------------------------------------------------------------------ templates

    /** A template's shift times or rules changed: the people who work it from today on are told. */
    void templateChanged(ShiftTemplate t, Collection<UUID> who) {
        if (!who.isEmpty()) {
            outbox.add(ScheduleChanged.TYPE, ScheduleChanged.shiftTimes(List.copyOf(who), t.getName(), times(t)));
        }
    }

    /** People for whom a date change of today or later with this template is the one that applies. */
    Set<UUID> peopleWithTemplateOnDateChanges(UUID templateId) {
        Set<UUID> who = new LinkedHashSet<>();
        UUID school = schoolContext.currentSchoolId();
        List<DateChange> using = ScopeFilter.withoutScope(
                () -> changes.findBySchoolIdAndTemplateIdAndDateGreaterThanEqual(school, templateId, LocalDate.now(clock)));
        for (DateChange c : using) {
            whoseDayIsThisTarget(c.getTargetType(), c.targetId(), List.of(c.getDate())).values().forEach(who::addAll);
        }
        return who;
    }

    // ------------------------------------------------------------------ helpers

    /** Active people of the target (whole school, whatever the caller's record scope). */
    List<PersonPlace> reached(Target target, UUID targetId) {
        return people.activePeople().stream().filter(p -> switch (target) {
            case EVERYONE -> true;
            case DEPARTMENT -> targetId.equals(p.departmentId());
            case PERSON -> targetId.equals(p.id());
        }).toList();
    }

    private List<PersonPlace> reachedBy(UUID departmentOrEveryone) {
        return departmentOrEveryone == null ? reached(Target.EVERYONE, null) : reached(Target.DEPARTMENT, departmentOrEveryone);
    }

    private static List<UUID> ids(List<PersonPlace> list) {
        return list.stream().map(PersonPlace::id).toList();
    }

    /** "07:45 – 11:30" per shift, in order. */
    static List<String> times(ShiftTemplate t) {
        return t.getParts().stream().sorted(Comparator.comparingInt(ShiftTemplate.Part::getPosition))
                .map(p -> p.getStart() + " – " + p.getEnd()).toList();
    }
}
