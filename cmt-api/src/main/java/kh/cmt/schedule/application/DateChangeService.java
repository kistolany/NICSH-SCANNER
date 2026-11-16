package kh.cmt.schedule.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgLabel;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.schedule.api.SpecialDays;
import kh.cmt.schedule.domain.DateChange;
import kh.cmt.schedule.domain.Holiday;
import kh.cmt.schedule.domain.WeeklySchedule.Target;
import kh.cmt.schedule.infrastructure.DateChangeRepository;
import kh.cmt.schedule.infrastructure.HolidayRepository;
import kh.cmt.schedule.infrastructure.ShiftTemplateRepository;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.security.ScopeFilter;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Date changes (Shifts &amp; schedules › Date changes) and holidays (Leave &amp; holidays › Holiday calendar).
 * <p>
 * <b>Date change:</b> on picked dates, everyone / a department / a person works another template, or is off, with a
 * label ("Exam day"). One per date and target (saving again replaces it); the most specific one of a date wins for a
 * person; it beats the weekly schedule. Only today or later can be set or removed: past days stay as they were.
 * <b>Holiday:</b> a national holiday or school break over one or more days, for everyone or one department; it beats
 * date changes (they do not apply on it). Holidays starting today or later can be changed or removed; past ones stay.
 * <p>
 * Permissions: date changes Shifts view / create / delete; holidays Leave &amp; holidays view / create / edit / delete.
 * Leave is not built yet (phase 9): the "this person is on leave that day" check comes with it.
 */
@Service
@Transactional
public class DateChangeService implements SpecialDays {

    static final int MAX_DATES = 62;
    static final int MAX_HOLIDAY_DAYS = 366;
    static final int MAX_LABEL = 40;

    private final DateChangeRepository changes;
    private final HolidayRepository holidays;
    private final ShiftTemplateRepository templates;
    private final OrgQueries org;
    private final PeopleQueries people;
    private final SchoolContext schoolContext;
    private final Clock clock;
    private final ScheduleNotices notices;

    DateChangeService(DateChangeRepository changes, HolidayRepository holidays, ShiftTemplateRepository templates, OrgQueries org,
            PeopleQueries people, SchoolContext schoolContext, Clock clock, ScheduleNotices notices) {
        this.changes = changes;
        this.holidays = holidays;
        this.templates = templates;
        this.org = org;
        this.people = people;
        this.schoolContext = schoolContext;
        this.clock = clock;
        this.notices = notices;
    }

    // ------------------------------------------------------------------ date changes

    /**
     * Date changes from {@code from} (today when left out) to {@code to} (a year later when left out), by date; a
     * person's are listed only within the caller's record scope.
     */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.VIEW)
    public List<DateChangeView.Change> listChanges(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? today() : from;
        LocalDate end = to == null ? start.plusYears(1) : to;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 2 * 366) {
            throw validation(List.of(error("to", "Range")));
        }
        List<DateChange> found = changes.findBySchoolIdAndDateBetweenOrderByDateAsc(school(), start, end);
        return views(found, holidaysBetween(start, end));
    }

    /**
     * Sets a date change on each picked date for the target; an existing one of the same date and target is replaced.
     * The answer tells which dates are holidays (the change does not apply there) and how many people it reaches.
     */
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.CREATE)
    public DateChangeView.Saved createChanges(DateChangeCommands.Create command) {
        List<Map<String, String>> errors = new ArrayList<>();
        Place place = place(command.target(), command.targetId(), errors);
        TreeSet<LocalDate> dates = new TreeSet<>();
        if (command.dates() == null || command.dates().isEmpty()) {
            errors.add(error("dates", "NotEmpty"));
        } else if (command.dates().size() > MAX_DATES) {
            errors.add(error("dates", "Size"));
        } else {
            for (LocalDate d : command.dates()) {
                if (d == null) {
                    errors.add(error("dates", "NotNull"));
                } else if (d.isBefore(today())) {
                    errors.add(error("dates", "Past"));   // what applied to past days stays
                } else {
                    dates.add(d);
                }
            }
        }
        if (command.templateId() != null && !activeTemplate(command.templateId())) {
            errors.add(error("templateId", "UnknownTemplate"));
        }
        String label = blankToNull(command.label());
        if (label != null && label.length() > MAX_LABEL) {
            errors.add(error("label", "Size"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors.stream().distinct().toList());
        }
        Map<LocalDate, DateChange> existing = new HashMap<>();
        ScopeFilter.withoutScope(() -> changes.findBySchoolIdAndDateIn(school(), List.copyOf(dates))).stream()
                .filter(c -> c.getTargetType() == place.target() && Objects.equals(c.targetId(), place.id()))
                .forEach(c -> existing.put(c.getDate(), c));
        List<DateChange> saved = new ArrayList<>();
        List<LocalDate> different = new ArrayList<>();   // new, or another template / label than before (to tell people)
        for (LocalDate d : dates) {
            DateChange before = existing.get(d);
            if (before == null || !Objects.equals(before.getTemplateId(), command.templateId()) || !Objects.equals(before.getLabel(), label)) {
                different.add(d);
            }
            DateChange change = before != null ? before : new DateChange(d, place.target(),
                    place.target() == Target.DEPARTMENT ? place.id() : null, place.target() == Target.PERSON ? place.id() : null);
            change.set(command.templateId(), label);
            saved.add(change);
        }
        try {
            saved = changes.saveAllAndFlush(saved);
        } catch (DataIntegrityViolationException parallel) {
            throw new ApiException(ErrorCode.CONFLICT);   // somebody set the same date at the same moment
        }
        notices.dateChangesSaved(place.target(), place.id(), different, command.templateId(), label);
        List<Holiday> hols = holidaysBetween(dates.first(), dates.last());
        List<LocalDate> holidayDates = dates.stream()
                .filter(d -> hols.stream().anyMatch(h -> h.covers(d) && reachesTarget(h, place.target(), place.id(), place.department())))
                .toList();
        return new DateChangeView.Saved(views(saved, hols), existing.size(), holidayDates, affected(place));
    }

    /** Removes a date change of today or later; the weekly schedule applies again that day. */
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.DELETE)
    public void deleteChange(UUID id) {
        DateChange change = changes.findById(id).filter(c -> c.getSchoolId().equals(school()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (change.getDate().isBefore(today())) {
            throw new ApiException(ErrorCode.IN_THE_PAST);
        }
        notices.dateChangeRemoving(change);
        changes.delete(change);
    }

    // ------------------------------------------------------------------ holidays

    /** Holidays with a day in {@code year} (this year when left out), by start date. */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.VIEW)
    public List<DateChangeView.HolidayItem> listHolidays(Integer year) {
        int y = year == null ? today().getYear() : year;
        if (y < 2000 || y > 2100) {
            throw validation(List.of(error("year", "Range")));
        }
        LocalDate from = LocalDate.of(y, 1, 1);
        LocalDate to = LocalDate.of(y, 12, 31);
        List<Holiday> found = holidays.overlapping(school(), from, to);
        return holidayViews(found);
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.VIEW)
    public DateChangeView.HolidayItem getHoliday(UUID id) {
        return holidayViews(List.of(loadHoliday(id))).get(0);
    }

    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.CREATE)
    public DateChangeView.HolidayItem createHoliday(DateChangeCommands.SaveHoliday command) {
        Valid v = check(command, null);
        Holiday holiday = holidays.saveAndFlush(new Holiday(v.name(), v.kind(), v.start(), v.end(), command.departmentId()));
        notices.holidayAdded(holiday);
        return holidayViews(List.of(holiday)).get(0);
    }

    /** Changes a holiday that starts today or later (send the version you read). */
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.EDIT)
    public DateChangeView.HolidayItem updateHoliday(UUID id, DateChangeCommands.SaveHoliday command) {
        Holiday holiday = loadHoliday(id);
        if (holiday.getStartDate().isBefore(today())) {
            throw new ApiException(ErrorCode.IN_THE_PAST);
        }
        Valid v = check(command, holiday);
        if (command.version() == null) {
            throw validation(List.of(error("version", "NotNull")));
        }
        if (command.version() != holiday.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        UUID departmentBefore = holiday.getDepartmentId();
        boolean different = !holiday.getName().equals(v.name()) || !holiday.getStartDate().equals(v.start())
                || !holiday.getEndDate().equals(v.end()) || !Objects.equals(departmentBefore, command.departmentId());
        holiday.set(v.name(), v.kind(), v.start(), v.end(), command.departmentId());
        holiday = holidays.saveAndFlush(holiday);
        if (different) {
            notices.holidayChanged(holiday, departmentBefore);
        }
        return holidayViews(List.of(holiday)).get(0);
    }

    /** Removes a holiday that starts today or later. */
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.DELETE)
    public void deleteHoliday(UUID id) {
        Holiday holiday = loadHoliday(id);
        if (holiday.getStartDate().isBefore(today())) {
            throw new ApiException(ErrorCode.IN_THE_PAST);
        }
        notices.holidayRemoving(holiday);
        holidays.delete(holiday);
    }

    // ------------------------------------------------------------------ for other modules (whole school)

    /** Of two holidays on one day, the national one, then the one that started first. */
    private static final Comparator<Holiday> HOLIDAY_FIRST = Comparator.comparing((Holiday h) -> h.getKind() == Holiday.Kind.NATIONAL ? 0 : 1)
            .thenComparing(Holiday::getStartDate);
    /** Of two date changes on one day, the most specific: PERSON > DEPARTMENT > EVERYONE. */
    private static final Comparator<DateChange> CHANGE_FIRST = Comparator.comparingInt((DateChange c) -> c.getTargetType().ordinal()).reversed();

    @Override
    @Transactional(readOnly = true)
    public Optional<HolidayDay> holidayOn(UUID departmentOfPerson, LocalDate day) {
        return Optional.ofNullable(holidaysIn(departmentOfPerson, day, day).get(day));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChangeDay> dateChangeOn(UUID personId, UUID departmentOfPerson, LocalDate day) {
        return Optional.ofNullable(dateChangesIn(personId, departmentOfPerson, day, day).get(day));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, HolidayDay> holidaysIn(UUID departmentOfPerson, LocalDate from, LocalDate to) {
        Map<LocalDate, Holiday> best = new HashMap<>();
        for (Holiday h : holidays.overlapping(school(), from, to)) {
            if (h.reaches(departmentOfPerson)) {
                for (LocalDate d = h.getStartDate().isBefore(from) ? from : h.getStartDate(); !d.isAfter(h.getEndDate()) && !d.isAfter(to);
                        d = d.plusDays(1)) {
                    best.merge(d, h, (a, b) -> HOLIDAY_FIRST.compare(a, b) <= 0 ? a : b);
                }
            }
        }
        Map<LocalDate, HolidayDay> out = new HashMap<>();
        best.forEach((d, h) -> out.put(d, new HolidayDay(h.getId(), h.getName(), h.getKind().name())));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, ChangeDay> dateChangesIn(UUID personId, UUID departmentOfPerson, LocalDate from, LocalDate to) {
        Map<LocalDate, DateChange> best = new HashMap<>();
        ScopeFilter.withoutScope(() -> changes.findBySchoolIdAndDateBetweenOrderByDateAsc(school(), from, to)).stream()
                .filter(c -> c.reaches(personId, departmentOfPerson))
                .forEach(c -> best.merge(c.getDate(), c, (a, b) -> CHANGE_FIRST.compare(a, b) <= 0 ? a : b));
        Map<LocalDate, ChangeDay> out = new HashMap<>();
        best.forEach((d, c) -> out.put(d, new ChangeDay(c.getId(), c.getTargetType().name(), c.getTemplateId(), c.getLabel())));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.SortedSet<LocalDate> specialDates(UUID personId, UUID departmentOfPerson, LocalDate from, LocalDate to) {
        TreeSet<LocalDate> out = new TreeSet<>();
        ScopeFilter.withoutScope(() -> changes.findBySchoolIdAndDateBetweenOrderByDateAsc(school(), from, to)).stream()
                .filter(c -> c.reaches(personId, departmentOfPerson)).forEach(c -> out.add(c.getDate()));
        for (Holiday h : holidays.overlapping(school(), from, to)) {
            if (h.reaches(departmentOfPerson)) {
                for (LocalDate d = h.getStartDate().isBefore(from) ? from : h.getStartDate(); !d.isAfter(h.getEndDate()) && !d.isAfter(to);
                        d = d.plusDays(1)) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /** Is the template on a date change of today or later (it must not be retired then)? */
    @Transactional(readOnly = true)
    public boolean templateUsedFromToday(UUID templateId) {
        return ScopeFilter.withoutScope(() -> changes.existsBySchoolIdAndTemplateIdAndDateGreaterThanEqual(school(), templateId, today()));
    }

    // ------------------------------------------------------------------ helpers

    /** The target, checked: an active department, or an active person within the caller's scope. */
    private record Place(Target target, UUID id, UUID department) {
    }

    private Place place(String target, UUID id, List<Map<String, String>> errors) {
        String t = target == null || target.isBlank() ? "everyone" : target.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "everyone", "all" -> {
                return new Place(Target.EVERYONE, null, null);
            }
            case "department" -> {
                if (id == null) {
                    throw validation(List.of(error("targetId", "NotNull")));
                }
                if (!org.isActive(OrgKind.DEPARTMENT, id)) {
                    throw new ApiException(ErrorCode.NOT_FOUND);
                }
                return new Place(Target.DEPARTMENT, id, id);
            }
            case "person" -> {
                if (id == null) {
                    throw validation(List.of(error("targetId", "NotNull")));
                }
                PeopleQueries.VisiblePerson p = people.visible(id).filter(PeopleQueries.VisiblePerson::active)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                return new Place(Target.PERSON, id, p.departmentId());
            }
            default -> {
                errors.add(error("target", "OneOf"));
                return new Place(Target.EVERYONE, null, null);
            }
        }
    }

    /** Does the holiday cover the target (for a person: through their department)? */
    private static boolean reachesTarget(Holiday h, Target target, UUID id, UUID department) {
        return switch (target) {
            case EVERYONE -> h.getDepartmentId() == null;
            case DEPARTMENT, PERSON -> h.reaches(department);
        };
    }

    private int affected(Place place) {
        return (int) people.activePeople().stream().filter(p -> switch (place.target()) {
            case EVERYONE -> true;
            case DEPARTMENT -> place.id().equals(p.departmentId());
            case PERSON -> place.id().equals(p.id());
        }).count();
    }

    private List<DateChangeView.Change> views(List<DateChange> list, List<Holiday> hols) {
        Map<UUID, WeeklyScheduleView.TemplateRef> refs = new HashMap<>();
        Set<UUID> deptIds = new HashSet<>();
        Set<UUID> personIds = new HashSet<>();
        list.forEach(c -> {
            if (c.getDepartmentId() != null) {
                deptIds.add(c.getDepartmentId());
            }
            if (c.getPersonId() != null) {
                personIds.add(c.getPersonId());
            }
        });
        Map<UUID, OrgLabel> depts = deptIds.isEmpty() ? Map.of() : org.labels(OrgKind.DEPARTMENT, deptIds);
        Map<UUID, PeopleQueries.PersonLabel> persons = people.labels(personIds);
        Map<UUID, UUID> deptOfPerson = new HashMap<>();
        personIds.forEach(p -> deptOfPerson.put(p, people.departmentOfPerson(p)));
        return list.stream().map(c -> {
            DateChangeView.Who who = switch (c.getTargetType()) {
                case EVERYONE -> new DateChangeView.Who("EVERYONE", null, null, null);
                case DEPARTMENT -> new DateChangeView.Who("DEPARTMENT", c.getDepartmentId(),
                        Optional.ofNullable(depts.get(c.getDepartmentId())).map(OrgLabel::name).orElse(null), null);
                case PERSON -> {
                    PeopleQueries.PersonLabel p = persons.get(c.getPersonId());
                    yield new DateChangeView.Who("PERSON", c.getPersonId(), p == null ? null : p.name(), p == null ? null : p.staffId());
                }
            };
            UUID department = c.getTargetType() == Target.PERSON ? deptOfPerson.get(c.getPersonId()) : c.getDepartmentId();
            boolean holiday = hols.stream().anyMatch(h -> h.covers(c.getDate()) && reachesTarget(h, c.getTargetType(), c.targetId(), department));
            WeeklyScheduleView.TemplateRef template = c.getTemplateId() == null ? null
                    : refs.computeIfAbsent(c.getTemplateId(), k -> templates.findById(k).map(WeeklyScheduleService::ref).orElse(null));
            return new DateChangeView.Change(c.getId(), c.getDate(), who, template == null, template, c.getLabel(), holiday,
                    c.getCreatedBy(), c.getCreatedAt());
        }).toList();
    }

    private List<DateChangeView.HolidayItem> holidayViews(List<Holiday> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> deptIds = new LinkedHashSet<>();
        list.forEach(h -> {
            if (h.getDepartmentId() != null) {
                deptIds.add(h.getDepartmentId());
            }
        });
        Map<UUID, OrgLabel> depts = deptIds.isEmpty() ? Map.of() : org.labels(OrgKind.DEPARTMENT, deptIds);
        LocalDate first = list.stream().map(Holiday::getStartDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = list.stream().map(Holiday::getEndDate).max(Comparator.naturalOrder()).orElseThrow();
        List<DateChange> onDays = ScopeFilter.withoutScope(() -> changes.findBySchoolIdAndDateBetweenOrderByDateAsc(school(), first, last));
        LocalDate today = today();
        return list.stream().map(h -> {
            DateChangeView.Who who = h.getDepartmentId() == null ? new DateChangeView.Who("EVERYONE", null, null, null)
                    : new DateChangeView.Who("DEPARTMENT", h.getDepartmentId(),
                            Optional.ofNullable(depts.get(h.getDepartmentId())).map(OrgLabel::name).orElse(null), null);
            int overridden = (int) onDays.stream().filter(c -> h.covers(c.getDate()) && (h.getDepartmentId() == null
                    || c.getTargetType() == Target.EVERYONE
                    || h.getDepartmentId().equals(c.getTargetType() == Target.PERSON ? people.departmentOfPerson(c.getPersonId()) : c.getDepartmentId())))
                    .count();
            return new DateChangeView.HolidayItem(h.getId(), h.getName(), h.getKind().name(), h.getStartDate(), h.getEndDate(),
                    (int) ChronoUnit.DAYS.between(h.getStartDate(), h.getEndDate()) + 1, who, h.getStartDate().isBefore(today),
                    overridden == 0 ? null : overridden, h.getVersion(), h.getCreatedAt(), h.getUpdatedAt());
        }).toList();
    }

    private record Valid(LocalizedName name, Holiday.Kind kind, LocalDate start, LocalDate end) {
    }

    private Valid check(DateChangeCommands.SaveHoliday in, Holiday current) {
        List<Map<String, String>> errors = new ArrayList<>();
        LocalizedName raw = in.name() == null ? new LocalizedName(null, null, null) : in.name();
        LocalizedName name = new LocalizedName(blankToNull(raw.km()), blankToNull(raw.en()), blankToNull(raw.ar()));
        if (name.km() == null && name.en() == null) {
            errors.add(error("name", "NotBlank"));
        }
        for (var part : List.of(Map.entry("name.km", Optional.ofNullable(name.km())), Map.entry("name.en", Optional.ofNullable(name.en())),
                Map.entry("name.ar", Optional.ofNullable(name.ar())))) {
            if (part.getValue().map(s -> s.length() > 100).orElse(false)) {
                errors.add(error(part.getKey(), "Size"));
            }
        }
        Holiday.Kind kind = null;
        if (in.kind() == null || in.kind().isBlank()) {
            errors.add(error("kind", "NotNull"));
        } else {
            try {
                kind = Holiday.Kind.valueOf(in.kind().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                errors.add(error("kind", "OneOf"));
            }
        }
        LocalDate start = in.startDate();
        LocalDate end = in.endDate() == null ? start : in.endDate();
        if (start == null) {
            errors.add(error("startDate", "NotNull"));
        } else {
            if (start.isBefore(today())) {
                errors.add(error("startDate", "Past"));   // a holiday is set before it starts
            }
            if (end.isBefore(start)) {
                errors.add(error("endDate", "EndBeforeStart"));
            } else if (ChronoUnit.DAYS.between(start, end) >= MAX_HOLIDAY_DAYS) {
                errors.add(error("endDate", "Range"));
            }
        }
        if (in.departmentId() != null && !org.isActive(OrgKind.DEPARTMENT, in.departmentId())
                && (current == null || !in.departmentId().equals(current.getDepartmentId()))) {
            errors.add(error("departmentId", "UnknownDepartment"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return new Valid(name, kind, start, end);
    }

    private Holiday loadHoliday(UUID id) {
        return holidays.findById(id).filter(h -> h.getSchoolId().equals(school()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private List<Holiday> holidaysBetween(LocalDate from, LocalDate to) {
        return holidays.overlapping(school(), from, to);
    }

    private boolean activeTemplate(UUID id) {
        return templates.findById(id).filter(t -> t.getSchoolId().equals(school()) && !t.isInactive()).isPresent();
    }

    private UUID school() {
        return schoolContext.currentSchoolId();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
