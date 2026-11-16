package kh.cmt.schedule.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.schedule.api.WeeklySchedules;
import kh.cmt.schedule.domain.ShiftTemplate;
import kh.cmt.schedule.domain.WeekResolution;
import kh.cmt.schedule.domain.WeeklySchedule;
import kh.cmt.schedule.domain.WeeklySchedule.Target;
import kh.cmt.schedule.infrastructure.ShiftTemplateRepository;
import kh.cmt.schedule.infrastructure.WeeklyScheduleRepository;
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
 * Weekly schedules (Shifts &amp; schedules › Weekly schedules): for everyone, a department or one person, Monday to
 * Sunday each a template or off, from a date on.
 * <p>
 * Every save is a version with its effective date (today or later), so the past stays as it was; saving on the date of
 * an existing version replaces it (a planned change can be corrected). The most specific level in force wins for a
 * person: their own, then their department's, then everyone's; a department or person can go back to the level above
 * from a date ({@code inherit}). Days take active templates only.
 * <p>
 * Permissions: Shifts view (read, preview) and edit (save). A person outside the caller's record scope is not found.
 */
@Service
@Transactional
public class WeeklyScheduleService implements WeeklySchedules {

    private final WeeklyScheduleRepository schedules;
    private final ShiftTemplateRepository templates;
    private final OrgQueries org;
    private final PeopleQueries people;
    private final SchoolContext schoolContext;
    private final Clock clock;
    private final ScheduleNotices notices;

    WeeklyScheduleService(WeeklyScheduleRepository schedules, ShiftTemplateRepository templates, OrgQueries org,
            PeopleQueries people, SchoolContext schoolContext, Clock clock, ScheduleNotices notices) {
        this.schedules = schedules;
        this.templates = templates;
        this.org = org;
        this.people = people;
        this.schoolContext = schoolContext;
        this.clock = clock;
        this.notices = notices;
    }

    // ------------------------------------------------------------------ the page

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.VIEW)
    public WeeklyScheduleView.TargetSchedule get(String target, UUID id) {
        Place place = place(target, id, false);
        return page(place, allVersions(), null);
    }

    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.EDIT)
    public WeeklyScheduleView.TargetSchedule save(String target, UUID id, WeeklyScheduleCommands.Save command) {
        Place place = place(target, id, true);
        List<java.util.Map<String, String>> errors = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        if (command.effectiveFrom() == null) {
            errors.add(error("effectiveFrom", "NotNull"));
        } else if (command.effectiveFrom().isBefore(today)) {
            errors.add(error("effectiveFrom", "Past"));   // what was planned for past days stays
        }
        boolean inherit = Boolean.TRUE.equals(command.inherit());
        List<WeeklySchedule.Day> days = new ArrayList<>();
        if (inherit) {
            if (place.target() == Target.EVERYONE) {
                errors.add(error("inherit", "NotForEveryone"));
            }
            if (command.days() != null && !command.days().isEmpty()) {
                errors.add(error("days", "NotWithInherit"));
            }
        } else {
            days = days(command.days(), errors);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
        List<WeeklySchedule> all = allVersions();
        Map<UUID, Map<Integer, UUID>> weeksBefore = notices.weeks(all, place.target(), place.id(), command.effectiveFrom());
        WeeklySchedule version = all.stream()
                .filter(v -> v.getTargetType() == place.target() && Objects.equals(WeekResolution.idOf(v), place.id())
                        && v.getEffectiveFrom().equals(command.effectiveFrom()))
                .findFirst()
                .orElseGet(() -> new WeeklySchedule(place.target(), place.target() == Target.DEPARTMENT ? place.id() : null,
                        place.target() == Target.PERSON ? place.id() : null, command.effectiveFrom()));
        version.set(inherit, days);
        try {
            version = schedules.saveAndFlush(version);
        } catch (DataIntegrityViolationException parallel) {
            throw new ApiException(ErrorCode.CONFLICT);   // somebody saved the same date at the same moment
        }
        List<WeeklySchedule> after = allVersions();
        notices.weekSaved(weeksBefore, after, place.target(), place.id(), command.effectiveFrom());
        return page(place, after, affected(place, version, after));
    }

    /** The grouped week that applies to the target on {@code date} (today when left out). */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.VIEW)
    public WeeklyScheduleView.Preview preview(String target, UUID id, LocalDate date) {
        Place place = place(target, id, false);
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        Optional<WeeklySchedule> applies = applies(place, allVersions(), day);
        Map<UUID, WeeklyScheduleView.TemplateRef> refs = new HashMap<>();
        return new WeeklyScheduleView.Preview(target(place), day, applies.map(v -> v.getTargetType().name()).orElse(null),
                applies.map(v -> groups(v, refs)).orElse(List.of()));
    }

    // ------------------------------------------------------------------ for the templates and other modules

    /** People per template in the week that applies to them today ("Used by N people"). */
    @Transactional(readOnly = true)
    public Map<UUID, Long> usageToday() {
        LocalDate today = LocalDate.now(clock);
        List<WeeklySchedule> all = allVersions();
        Map<UUID, Long> usage = new HashMap<>();
        for (PeopleQueries.PersonPlace p : people.activePeople()) {
            WeekResolution.forPerson(all, p.id(), p.departmentId(), today).ifPresent(v -> v.getDays().stream()
                    .map(WeeklySchedule.Day::getTemplateId).filter(Objects::nonNull).distinct()
                    .forEach(t -> usage.merge(t, 1L, Long::sum)));
        }
        return usage;
    }

    /** Is the template in a version that is in force or still to come (it must not be retired then)? */
    @Transactional(readOnly = true)
    public boolean usedNowOrLater(UUID templateId) {
        LocalDate today = LocalDate.now(clock);
        List<WeeklySchedule> all = allVersions();
        for (WeeklySchedule v : all) {
            boolean uses = v.getDays().stream().anyMatch(d -> templateId.equals(d.getTemplateId()));
            if (!uses) {
                continue;
            }
            Optional<WeeklySchedule> inForce = WeekResolution.inForce(all, v.getTargetType(), WeekResolution.idOf(v), today);
            if (v.getEffectiveFrom().isAfter(today) || inForce.map(x -> x.getId().equals(v.getId())).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Active people who work the template from today on through their weekly schedule: in the week that applies to
     * them today, or in one that starts later.
     */
    @Transactional(readOnly = true)
    public Set<UUID> peopleUsing(UUID templateId) {
        LocalDate today = LocalDate.now(clock);
        List<WeeklySchedule> all = allVersions();
        List<LocalDate> days = new ArrayList<>(List.of(today));
        all.stream().map(WeeklySchedule::getEffectiveFrom).filter(d -> d.isAfter(today)).distinct().forEach(days::add);
        Set<UUID> who = new HashSet<>();
        for (PeopleQueries.PersonPlace p : people.activePeople()) {
            for (LocalDate day : days) {
                boolean uses = WeekResolution.forPerson(all, p.id(), p.departmentId(), day)
                        .map(v -> v.getDays().stream().anyMatch(d -> templateId.equals(d.getTemplateId()))).orElse(false);
                if (uses) {
                    who.add(p.id());
                    break;
                }
            }
        }
        return who;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Week> weekOf(UUID personId, LocalDate day) {
        UUID department = people.departmentOfPerson(personId);
        return WeekResolution.forPerson(allVersions(), personId, department, day).map(WeeklyScheduleService::week);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, Week> weeksOf(UUID personId, LocalDate from, LocalDate to) {
        UUID department = people.departmentOfPerson(personId);
        List<WeeklySchedule> all = allVersions();   // once for the whole range
        Map<LocalDate, Week> out = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate day = d;
            WeekResolution.forPerson(all, personId, department, day).ifPresent(v -> out.put(day, week(v)));
        }
        return out;
    }

    private static Week week(WeeklySchedule v) {
        Map<Integer, UUID> week = new HashMap<>();
        v.getDays().forEach(d -> {
            if (d.getTemplateId() != null) {
                week.put(d.getWeekday(), d.getTemplateId());
            }
        });
        return new Week(v.getTargetType().name(), v.getEffectiveFrom(), week);
    }

    // ------------------------------------------------------------------ helpers

    /** The target of a request, checked: department active (to save) / known; person in the caller's scope. */
    private record Place(Target target, UUID id, UUID departmentOfPerson) {
    }

    private Place place(String target, UUID id, boolean toSave) {
        String t = target == null ? "everyone" : target.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "everyone", "all" -> {
                return new Place(Target.EVERYONE, null, null);
            }
            case "department" -> {
                if (id == null) {
                    throw validationOf("id", "NotNull");
                }
                boolean known = toSave ? org.isActive(OrgKind.DEPARTMENT, id) : !org.labels(OrgKind.DEPARTMENT, List.of(id)).isEmpty();
                if (!known) {
                    throw new ApiException(ErrorCode.NOT_FOUND);
                }
                return new Place(Target.DEPARTMENT, id, null);
            }
            case "person" -> {
                if (id == null) {
                    throw validationOf("id", "NotNull");
                }
                PeopleQueries.VisiblePerson person = people.visible(id).filter(p -> !toSave || p.active())
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                return new Place(Target.PERSON, id, person.departmentId());
            }
            default -> throw validationOf("target", "OneOf");
        }
    }

    /** Exactly the seven ISO weekdays once each; templates active ones of the school (null = off). */
    private List<WeeklySchedule.Day> days(List<WeeklyScheduleCommands.Day> in, List<java.util.Map<String, String>> errors) {
        List<WeeklySchedule.Day> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (WeeklyScheduleCommands.Day d : in == null ? List.<WeeklyScheduleCommands.Day>of() : in) {
            if (d == null || d.weekday() == null || d.weekday() < 1 || d.weekday() > 7 || !seen.add(d.weekday())) {
                errors.add(error("days", "Weekdays"));
                continue;
            }
            if (d.templateId() != null && !activeTemplate(d.templateId())) {
                errors.add(error("days[" + d.weekday() + "].templateId", "UnknownTemplate"));
            }
            out.add(new WeeklySchedule.Day(d.weekday(), d.templateId()));
        }
        if (seen.size() != 7) {
            errors.add(error("days", "Weekdays"));   // Monday to Sunday, each once
        }
        out.sort(Comparator.comparingInt(WeeklySchedule.Day::getWeekday));
        return out;
    }

    private boolean activeTemplate(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return templates.findById(id).filter(t -> t.getSchoolId().equals(school) && !t.isInactive()).isPresent();
    }

    /** Every version of the school, whatever the caller's scope (the resolution must be complete). */
    private List<WeeklySchedule> allVersions() {
        UUID school = schoolContext.currentSchoolId();
        return ScopeFilter.withoutScope(() -> schedules.findBySchoolId(school));
    }

    private Optional<WeeklySchedule> applies(Place place, List<WeeklySchedule> all, LocalDate day) {
        return place.target() == Target.PERSON ? WeekResolution.forPerson(all, place.id(), place.departmentOfPerson(), day)
                : WeekResolution.forTarget(all, place.target(), place.id(), day);
    }

    private WeeklyScheduleView.TargetSchedule page(Place place, List<WeeklySchedule> all, Integer affected) {
        LocalDate today = LocalDate.now(clock);
        Map<UUID, WeeklyScheduleView.TemplateRef> refs = new HashMap<>();
        List<WeeklySchedule> own = all.stream()
                .filter(v -> v.getTargetType() == place.target() && Objects.equals(WeekResolution.idOf(v), place.id()))
                .sorted(Comparator.comparing(WeeklySchedule::getEffectiveFrom).reversed()).toList();
        Optional<WeeklySchedule> inForce = WeekResolution.inForce(all, place.target(), place.id(), today);
        List<WeeklyScheduleView.Version> history = new ArrayList<>();
        WeeklyScheduleView.Version current = null;
        List<WeeklyScheduleView.Version> upcoming = new ArrayList<>();
        for (WeeklySchedule v : own) {
            String status = v.getEffectiveFrom().isAfter(today) ? "UPCOMING"
                    : inForce.map(x -> x.getId().equals(v.getId())).orElse(false) ? "CURRENT" : "PAST";
            WeeklyScheduleView.Version view = new WeeklyScheduleView.Version(v.getId(), v.getEffectiveFrom(), v.isInherit(), status,
                    v.isInherit() ? null : days(v, refs), v.getCreatedBy(), v.getCreatedAt());
            history.add(view);
            if (status.equals("CURRENT")) {
                current = view;
            } else if (status.equals("UPCOMING")) {
                upcoming.add(0, view);   // soonest first
            }
        }
        WeeklyScheduleView.Applies applies = applies(place, all, today)
                .map(v -> new WeeklyScheduleView.Applies(v.getTargetType().name(), v.getEffectiveFrom(), days(v, refs)))
                .orElse(new WeeklyScheduleView.Applies(null, null, null));
        return new WeeklyScheduleView.TargetSchedule(target(place), current, upcoming, history, applies, affected);
    }

    /** How many active people the saved version reaches on its first day (the person / department / everybody). */
    private int affected(Place place, WeeklySchedule saved, List<WeeklySchedule> all) {
        int count = 0;
        for (PeopleQueries.PersonPlace p : people.activePeople()) {
            boolean ofTarget = switch (place.target()) {
                case EVERYONE -> true;
                case DEPARTMENT -> place.id().equals(p.departmentId());
                case PERSON -> place.id().equals(p.id());
            };
            if (!ofTarget) {
                continue;
            }
            if (saved.isInherit()) {
                // the level gave up its own week: everybody of it who has no more specific week of their own
                Optional<WeeklySchedule> applies = WeekResolution.forPerson(all, p.id(), p.departmentId(), saved.getEffectiveFrom());
                if (applies.map(v -> v.getTargetType().ordinal() < place.target().ordinal()).orElse(true)) {
                    count++;
                }
            } else if (WeekResolution.forPerson(all, p.id(), p.departmentId(), saved.getEffectiveFrom())
                    .map(v -> v.getId().equals(saved.getId())).orElse(false)) {
                count++;
            }
        }
        return count;
    }

    private List<WeeklyScheduleView.Day> days(WeeklySchedule v, Map<UUID, WeeklyScheduleView.TemplateRef> refs) {
        List<WeeklyScheduleView.Day> out = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            UUID templateId = v.templateOn(weekday);
            WeeklyScheduleView.TemplateRef ref = templateId == null ? null : ref(templateId, refs);
            out.add(new WeeklyScheduleView.Day(weekday, ref == null, ref));
        }
        return out;
    }

    /** Days with the same template together, in week order; "Monday – Friday" for three or more days in a row. */
    private List<WeeklyScheduleView.Group> groups(WeeklySchedule v, Map<UUID, WeeklyScheduleView.TemplateRef> refs) {
        Map<String, List<Integer>> byTemplate = new LinkedHashMap<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            UUID templateId = v.templateOn(weekday);
            byTemplate.computeIfAbsent(templateId == null ? "off" : templateId.toString(), k -> new ArrayList<>()).add(weekday);
        }
        List<WeeklyScheduleView.Group> groups = new ArrayList<>();
        byTemplate.forEach((key, weekdays) -> {
            WeeklyScheduleView.TemplateRef ref = key.equals("off") ? null : ref(UUID.fromString(key), refs);
            List<String> lines = new ArrayList<>();
            if (ref == null) {
                lines.add("Off");
            } else {
                for (WeeklyScheduleView.Shift s : ref.shifts()) {
                    String prefix = s.name() != null ? s.name() + " · " : ref.shifts().size() > 1 ? "Shift " + s.position() + " · " : "";
                    lines.add(prefix + s.start() + " – " + s.end());
                }
            }
            groups.add(new WeeklyScheduleView.Group(weekdays, label(weekdays), ref == null, ref, lines));
        });
        return groups;
    }

    static String label(List<Integer> weekdays) {
        boolean consecutive = true;
        for (int i = 1; i < weekdays.size(); i++) {
            consecutive &= weekdays.get(i) == weekdays.get(i - 1) + 1;
        }
        if (consecutive && weekdays.size() >= 3) {
            return name(weekdays.get(0)) + " – " + name(weekdays.get(weekdays.size() - 1));
        }
        return String.join(", ", weekdays.stream().map(WeeklyScheduleService::name).toList());
    }

    private static String name(int weekday) {
        return DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private WeeklyScheduleView.TemplateRef ref(UUID id, Map<UUID, WeeklyScheduleView.TemplateRef> refs) {
        return refs.computeIfAbsent(id, key -> templates.findById(key).map(WeeklyScheduleService::ref).orElse(null));
    }

    static WeeklyScheduleView.TemplateRef ref(ShiftTemplate t) {
        return new WeeklyScheduleView.TemplateRef(t.getId(), t.getName(), t.getColor(), !t.isInactive(), t.getParts().stream()
                .map(p -> new WeeklyScheduleView.Shift(p.getPosition(), p.getName(), p.getStart(), p.getEnd())).toList());
    }

    private static WeeklyScheduleView.Target target(Place place) {
        return new WeeklyScheduleView.Target(place.target().name(), place.id());
    }

    private static java.util.Map<String, String> error(String field, String rule) {
        return java.util.Map.of("field", field, "rule", rule);
    }

    private static ApiException validationOf(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(error(field, rule)));
    }
}
