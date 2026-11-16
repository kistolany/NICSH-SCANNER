package kh.cmt.schedule.application;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import kh.cmt.org.api.AttendanceRules;
import kh.cmt.org.api.SchoolSettingsQueries;
import kh.cmt.schedule.api.ShiftTemplateQueries;
import kh.cmt.schedule.domain.ShiftTemplate;
import kh.cmt.schedule.infrastructure.ShiftTemplateRepository;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift templates (Shifts &amp; schedules › Shift templates): list, create, change, move to inactive, restore, delete
 * forever.
 * <p>
 * Rules: a name (unique among active templates of the school); 1 or 2 shifts, each ending after it starts on the same
 * day, the second starting when or after the first ends (no overlap); grace 0–120 minutes and "early check-out needs a
 * reason" default to the school's attendance settings; a colour (#RRGGBB). Changing a template applies to every
 * schedule that uses it; the people who work it from today on are told when its times or rules change.
 * <p>
 * Permissions in the Shifts module: view, create, edit (also restore), delete (inactive; forever: Super Admin, only
 * inactive templates nothing uses any more).
 */
@Service
@Transactional
public class ShiftTemplateService implements ShiftTemplateQueries {

    static final String DEFAULT_COLOR = "#EA580C";
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    private final ShiftTemplateRepository templates;
    private final SchoolSettingsQueries settings;
    private final SchoolContext schoolContext;
    private final EntityManager em;
    private final Clock clock;
    private final WeeklyScheduleService weekly;
    private final DateChangeService dateChanges;
    private final ScheduleNotices notices;

    ShiftTemplateService(ShiftTemplateRepository templates, SchoolSettingsQueries settings, SchoolContext schoolContext,
            EntityManager em, Clock clock, WeeklyScheduleService weekly, DateChangeService dateChanges, ScheduleNotices notices) {
        this.weekly = weekly;
        this.dateChanges = dateChanges;
        this.notices = notices;
        this.templates = templates;
        this.settings = settings;
        this.schoolContext = schoolContext;
        this.em = em;
        this.clock = clock;
    }

    /** {@code status} active (default) | inactive; sorted by name. */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.VIEW)
    public List<ShiftTemplateView> list(String status) {
        boolean inactive = "inactive".equals(status);
        if (status != null && !status.isBlank() && !inactive && !"active".equals(status)) {
            throw validation(List.of(error("status", "OneOf")));
        }
        if (inactive) {
            SoftDeleteFilter.includeInactive(em);
        }
        Map<UUID, Long> usage = weekly.usageToday();
        return templates.findBySchoolIdAndInactiveOrderByNameAsc(schoolContext.currentSchoolId(), inactive).stream()
                .map(t -> view(t, usage)).toList();
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.VIEW)
    public ShiftTemplateView get(UUID id) {
        return view(load(id), weekly.usageToday());
    }

    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.CREATE)
    public ShiftTemplateView create(ShiftTemplateCommands.Save command) {
        ShiftTemplate template = new ShiftTemplate();
        apply(template, command, null);
        return view(save(template), Map.of());
    }

    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.EDIT)
    public ShiftTemplateView update(UUID id, ShiftTemplateCommands.Save command) {
        ShiftTemplate template = load(id);
        if (template.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);   // restore first
        }
        if (command.version() == null) {
            throw validation(List.of(error("version", "NotNull")));
        }
        if (command.version() != template.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        String rulesBefore = rules(template);
        apply(template, command, id);
        template = save(template);
        if (!rules(template).equals(rulesBefore)) {   // times, grace or early rule: tell the people who work it
            java.util.Set<UUID> who = new java.util.LinkedHashSet<>(weekly.peopleUsing(id));
            who.addAll(notices.peopleWithTemplateOnDateChanges(id));
            notices.templateChanged(template, who);
        }
        return view(template, weekly.usageToday());
    }

    /** What people notice of a template: its shift times, grace and early check-out rule (not its name or colour). */
    private static String rules(ShiftTemplate t) {
        return ScheduleNotices.times(t) + "|" + t.getGraceMinutes() + "|" + t.isEarlyNeedsReason() + "|" + t.getCheckInOpensMinutes();
    }

    /**
     * Moves the template to inactive (hidden from the lists to pick from); repeating it changes nothing. Refused while
     * a weekly schedule in force or still to come, or a date change of today or later, uses it ({@code IN_USE}):
     * change those first.
     */
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.DELETE)
    public void inactivate(UUID id, String reason) {
        ShiftTemplate template = load(id);
        if (!template.isInactive()) {
            if (weekly.usedNowOrLater(id) || dateChanges.templateUsedFromToday(id)) {
                throw new ApiException(ErrorCode.IN_USE);
            }
            String why = reason == null || reason.isBlank() ? null : reason.trim();
            template.inactivate(CurrentUser.require().userId(), clock.instant(), why);
            save(template);
        }
    }

    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.EDIT)
    public ShiftTemplateView restore(UUID id) {
        ShiftTemplate template = load(id);
        if (template.isInactive()) {
            if (templates.existsBySchoolIdAndNameIgnoreCaseAndIdNot(template.getSchoolId(), template.getName(), id)) {
                throw new ApiException(ErrorCode.NAME_TAKEN);   // the name was given to another one meanwhile
            }
            template.restore();
            template = save(template);
        }
        return view(template, weekly.usageToday());
    }

    /** Removes an inactive template for good: Super Admin only; refused while schedules still point at it. */
    @RequirePermission(module = PermissionModule.SHIFTS, action = PermissionAction.DELETE)
    public void deleteForever(UUID id) {
        if (!"super_admin".equals(CurrentUser.require().role())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        ShiftTemplate template = load(id);
        if (!template.isInactive()) {
            throw new ApiException(ErrorCode.NOT_INACTIVE);
        }
        try {
            templates.delete(template);
            templates.flush();
        } catch (DataIntegrityViolationException stillUsed) {
            throw new ApiException(ErrorCode.IN_USE);
        }
    }

    // ------------------------------------------------------------------ for other modules

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateInfo> find(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return id == null ? Optional.empty() : templates.findById(id).filter(t -> t.getSchoolId().equals(school))
                .map(ShiftTemplateService::info);
    }

    // ------------------------------------------------------------------ checks

    private void apply(ShiftTemplate template, ShiftTemplateCommands.Save c, UUID exceptId) {
        List<Map<String, String>> errors = new ArrayList<>();
        String name = c.name() == null ? "" : c.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            errors.add(error("name", "NotBlank"));
        } else if (name.length() > 60) {
            errors.add(error("name", "Size"));
        }
        List<ShiftTemplate.Part> parts = new ArrayList<>();
        List<ShiftTemplateCommands.Shift> shifts = c.shifts() == null ? List.of() : c.shifts();
        if (shifts.isEmpty() || shifts.size() > 2) {
            errors.add(error("shifts", "Size"));
        }
        for (int i = 0; i < Math.min(shifts.size(), 2); i++) {
            ShiftTemplateCommands.Shift s = shifts.get(i);
            String field = "shifts[" + i + "]";
            if (s == null || s.start() == null || s.end() == null) {
                errors.add(error(field, "NotNull"));
                continue;
            }
            if (!s.end().isAfter(s.start())) {
                errors.add(error(field + ".end", "EndBeforeStart"));
            }
            String partName = s.name() == null || s.name().isBlank() ? null : s.name().trim();
            if (partName != null && partName.length() > 30) {
                errors.add(error(field + ".name", "Size"));
            }
            parts.add(new ShiftTemplate.Part(i + 1, partName, s.start(), s.end()));
        }
        if (parts.size() == 2 && parts.get(0).getEnd().isAfter(parts.get(1).getStart())) {
            errors.add(error("shifts[1].start", "Overlap"));   // "Shift 1 ends after Shift 2 starts"
        }
        AttendanceRules school = settings.attendanceRules();
        int grace = c.graceMinutes() == null ? school.defaultGraceMinutes() : c.graceMinutes();
        if (grace < 0 || grace > 120) {
            errors.add(error("graceMinutes", "Range"));
        }
        if (c.checkInOpensMinutes() != null && (c.checkInOpensMinutes() < 0 || c.checkInOpensMinutes() > 720)) {
            errors.add(error("checkInOpensMinutes", "Range"));
        }
        String color = c.color() == null || c.color().isBlank() ? DEFAULT_COLOR : c.color().trim();
        if (!COLOR.matcher(color).matches()) {
            errors.add(error("color", "Pattern"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        if (templates.existsBySchoolIdAndNameIgnoreCaseAndIdNot(schoolContext.currentSchoolId(), name,
                exceptId == null ? new UUID(0, 0) : exceptId)) {
            throw new ApiException(ErrorCode.NAME_TAKEN);
        }
        template.setName(name);
        template.setParts(parts);
        template.setGraceMinutes(grace);
        template.setEarlyNeedsReason(c.earlyCheckOutNeedsReason() == null ? school.earlyCheckOutNeedsReason()
                : c.earlyCheckOutNeedsReason());
        template.setColor(color.toUpperCase(Locale.ROOT));
        template.setCheckInOpensMinutes(c.checkInOpensMinutes());
    }

    // ------------------------------------------------------------------ helpers

    /** A template of the caller's school, active or inactive. */
    private ShiftTemplate load(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return templates.findById(id).filter(t -> t.getSchoolId().equals(school))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private ShiftTemplate save(ShiftTemplate template) {
        try {
            return templates.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.NAME_TAKEN);   // a parallel request took the name first
        }
    }

    private static ShiftTemplateView view(ShiftTemplate t, Map<UUID, Long> usage) {
        List<ShiftTemplateView.Shift> shifts = t.getParts().stream()
                .map(p -> new ShiftTemplateView.Shift(p.getPosition(), p.getName(), p.getStart(), p.getEnd(), p.minutes())).toList();
        return new ShiftTemplateView(t.getId(), t.getName(), t.getColor(), shifts,
                shifts.stream().mapToLong(ShiftTemplateView.Shift::minutes).sum(), t.getGraceMinutes(), t.isEarlyNeedsReason(),
                t.getCheckInOpensMinutes(), usage.getOrDefault(t.getId(), 0L), t.isInactive(), t.getDeletedAt(), t.getDeletedBy(), t.getDeleteReason(), t.getVersion(), t.getCreatedAt(),
                t.getUpdatedAt());
    }

    private static TemplateInfo info(ShiftTemplate t) {
        return new TemplateInfo(t.getId(), t.getName(), t.getColor(), t.getGraceMinutes(), t.isEarlyNeedsReason(), !t.isInactive(),
                t.getParts().stream().map(p -> new TemplateInfo.Shift(p.getPosition(), p.getName(), p.getStart(), p.getEnd())).toList(),
                t.getCheckInOpensMinutes());
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
