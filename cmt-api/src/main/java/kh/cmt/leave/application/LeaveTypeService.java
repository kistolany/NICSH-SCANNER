package kh.cmt.leave.application;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kh.cmt.leave.domain.LeaveType;
import kh.cmt.leave.infrastructure.LeaveTypeRepository;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave &amp; holidays › Leave types (step 9.1): list, create, change, move to inactive (kept for history: balances and
 * the apps leave it out), restore. The English name is unique among the active types; the code is made from it once
 * ("Study leave" → {@code study}) and never changes. Permissions: Leave view / create / edit / delete.
 */
@Service
@Transactional
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LeaveTypeService {

    static final int NAME_MAX = 100;
    static final int CODE_MAX = 30;
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final BigDecimal MAX_DAYS = BigDecimal.valueOf(366);
    private static final UUID NONE = new UUID(0, 0);

    private final LeaveTypeRepository types;
    private final SchoolContext schoolContext;
    private final LeaveMapper mapper;
    private final EntityManager em;
    private final java.time.Clock clock;

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.VIEW)
    public List<LeaveViews.Type> list(boolean inactive) {
        if (inactive) {
            SoftDeleteFilter.includeInactive(em);
        }
        return types.findBySchoolIdOrderByPositionAscNameEnAsc(schoolContext.currentSchoolId()).stream()
                .filter(t -> t.isInactive() == inactive).map(mapper::type).toList();
    }

    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.CREATE)
    public LeaveViews.Type create(LeaveCommands.Type c) {
        Clean clean = check(c, NONE);
        LeaveType type = new LeaveType(freeCode(clean.name().getEn()), types.lastPosition(schoolContext.currentSchoolId()) + 1, clean.name(),
                clean.days(), clean.halfDays(), clean.attachment(), clean.color());
        return mapper.type(save(type));
    }

    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.EDIT)
    public LeaveViews.Type update(UUID id, LeaveCommands.Type c) {
        LeaveType type = load(id);
        Clean clean = check(c, id);
        if (c.version() == null) {
            throw validation(List.of(error("version", "NotNull")));
        }
        if (c.version() != type.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        type.set(clean.name(), clean.days(), clean.halfDays(), clean.attachment(), clean.color());
        return mapper.type(save(type));
    }

    /** {@code reason} optional. Balances and requests of the type stay; it is no longer offered. */
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.DELETE)
    public void inactivate(UUID id, String reason) {
        LeaveType type = load(id);
        if (!type.isInactive()) {
            type.inactivate(CurrentUser.require().userId(), clock.instant(), reason == null || reason.isBlank() ? null : reason.trim());
        }
    }

    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.EDIT)
    public LeaveViews.Type restore(UUID id) {
        LeaveType type = load(id);
        if (!type.isInactive()) {
            throw new ApiException(ErrorCode.NOT_INACTIVE);
        }
        if (types.existsBySchoolIdAndNameEnIgnoreCaseAndIdNot(type.getSchoolId(), type.getName().getEn(), id)) {
            throw new ApiException(ErrorCode.NAME_TAKEN);   // an active type took the name meanwhile
        }
        type.restore();
        return mapper.type(save(type));
    }

    // ------------------------------------------------------------------ helpers

    private record Clean(LocalizedText name, BigDecimal days, boolean halfDays, LeaveType.Attachment attachment, String color) {
    }

    /** The cleaned command; every problem at once, then the name. */
    private Clean check(LeaveCommands.Type c, UUID exceptId) {
        List<Map<String, String>> errors = new ArrayList<>();
        LocalizedName n = c.name() == null ? new LocalizedName(null, null, null) : c.name();
        String en = trim(n.en());
        if (en == null) {
            errors.add(error("name.en", "NotBlank"));
        }
        for (var part : Map.of("name.km", nullToEmpty(n.km()), "name.en", nullToEmpty(n.en()), "name.ar", nullToEmpty(n.ar())).entrySet()) {
            if (part.getValue().trim().length() > NAME_MAX) {
                errors.add(error(part.getKey(), "Size"));
            }
        }
        BigDecimal days = c.daysPerYear();
        if (days == null) {
            errors.add(error("daysPerYear", "NotNull"));
        } else if (days.signum() < 0 || days.compareTo(MAX_DAYS) > 0) {
            errors.add(error("daysPerYear", "Range"));
        } else if (days.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
            errors.add(error("daysPerYear", "Step"));   // whole or half days
        }
        LeaveType.Attachment attachment = LeaveType.Attachment.NONE;
        if (c.attachment() != null) {
            try {
                attachment = LeaveType.Attachment.valueOf(c.attachment().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException wrong) {
                errors.add(error("attachment", "OneOf"));
            }
        }
        if (c.color() == null || !COLOR.matcher(c.color().trim()).matches()) {
            errors.add(error("color", "Pattern"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        if (types.existsBySchoolIdAndNameEnIgnoreCaseAndIdNot(schoolContext.currentSchoolId(), en, exceptId)) {
            throw new ApiException(ErrorCode.NAME_TAKEN);
        }
        return new Clean(new LocalizedText(trim(n.km()), en, trim(n.ar())), days.setScale(1), c.halfDays() == null || c.halfDays(), attachment,
                c.color().trim().toUpperCase(Locale.ROOT));
    }

    /** "Study leave" → study (study_2… when taken, inactive types too). */
    private String freeCode(String nameEn) {
        String base = Arrays.stream(nameEn.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> !w.isEmpty() && !w.equals("leave")).collect(Collectors.joining("_"));
        base = base.isEmpty() ? "type" : base.length() > CODE_MAX - 3 ? base.substring(0, CODE_MAX - 3) : base;
        UUID school = schoolContext.currentSchoolId();
        SoftDeleteFilter.includeInactive(em);
        try {
            String code = base;
            for (int i = 2; types.existsBySchoolIdAndCode(school, code); i++) {
                code = base + "_" + i;
            }
            return code;
        } finally {
            SoftDeleteFilter.activeOnly(em);
        }
    }

    private LeaveType load(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return types.findById(id).filter(t -> t.getSchoolId().equals(school)).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private LeaveType save(LeaveType type) {
        try {
            return types.saveAndFlush(type);
        } catch (DataIntegrityViolationException parallel) {
            throw new ApiException(ErrorCode.NAME_TAKEN);   // the same name (or code) saved at the same moment
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
