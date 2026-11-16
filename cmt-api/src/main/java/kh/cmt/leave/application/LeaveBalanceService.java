package kh.cmt.leave.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.leave.api.MyLeaveBalances;
import kh.cmt.leave.domain.LeaveAdjustment;
import kh.cmt.leave.domain.LeaveType;
import kh.cmt.leave.domain.LeaveRequest;
import kh.cmt.leave.infrastructure.LeaveAdjustmentRepository;
import kh.cmt.leave.infrastructure.LeaveRequestRepository;
import kh.cmt.leave.infrastructure.LeaveTypeRepository;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave balances (Leave &amp; holidays › Balances, the app's Leave page and Home; step 9.1). Nothing is stored but the
 * admins' adjustments: per person, active type and calendar year, entitled = {@link Entitlements}, total = entitled +
 * adjustments, used = approved requests of the year, left = total − used, pending = requests still waiting.
 * <p>
 * The table lists the active people the caller may see; Adjust adds (or takes away) days with a reason, this year,
 * last year or next year, and may not bring the total below what was used. Permissions: Leave view (table, person),
 * Leave edit (adjust).
 */
@Service
@Transactional
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LeaveBalanceService implements MyLeaveBalances {

    static final int REASON_MAX = 300;
    private static final BigDecimal MAX_DAYS = BigDecimal.valueOf(366);

    private final LeaveTypeRepository types;
    private final LeaveAdjustmentRepository adjustments;
    private final LeaveRequestRepository requests;
    private final PeopleQueries people;
    private final AccountNames accountNames;
    private final SchoolContext schoolContext;
    private final LeaveMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.VIEW)
    public LeaveViews.Table table(Integer year) {
        int y = year(year);
        List<LeaveType> active = activeTypes();
        List<PeopleQueries.PersonCard> visible = people.visibleActive();
        Set<UUID> ids = visible.stream().map(PeopleQueries.PersonCard::id).collect(Collectors.toSet());
        Balances balances = balances(active, ids, y);
        List<LeaveViews.Row> rows = visible.stream().map(p -> new LeaveViews.Row(person(p, balances.starts().get(p.id())),
                balances.cells(p.id()))).toList();
        return new LeaveViews.Table(y, active.stream().map(mapper::type).toList(), rows);
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.VIEW)
    public LeaveViews.PersonBalances person(UUID personId, Integer year) {
        return personBalances(visibleActive(personId), year(year));
    }

    @RequirePermission(module = PermissionModule.LEAVE, action = PermissionAction.EDIT)
    public LeaveViews.PersonBalances adjust(UUID personId, LeaveCommands.Adjust c) {
        PeopleQueries.PersonCard person = visibleActive(personId);
        List<Map<String, String>> errors = new ArrayList<>();
        int thisYear = today().getYear();
        int y = c.year() == null ? thisYear : c.year();
        if (y < thisYear - 1 || y > thisYear + 1) {
            errors.add(error("year", "Range"));   // last year, this year or next year
        }
        LeaveType type = c.typeId() == null ? null : activeTypes().stream().filter(t -> t.getId().equals(c.typeId())).findFirst().orElse(null);
        if (c.typeId() == null) {
            errors.add(error("typeId", "NotNull"));
        } else if (type == null) {
            errors.add(error("typeId", "Unknown"));
        }
        BigDecimal days = c.days();
        if (days == null) {
            errors.add(error("days", "NotNull"));
        } else if (days.signum() == 0 || days.abs().compareTo(MAX_DAYS) > 0) {
            errors.add(error("days", "Range"));
        } else if (days.multiply(type == null || type.isHalfDays() ? BigDecimal.TWO : BigDecimal.ONE).stripTrailingZeros().scale() > 0) {
            errors.add(error("days", "Step"));   // half days only for a type taken by half days
        }
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.isEmpty()) {
            errors.add(error("reason", "NotBlank"));
        } else if (reason.length() > REASON_MAX) {
            errors.add(error("reason", "Size"));
        }
        if (errors.isEmpty()) {
            LeaveViews.Cell now = balances(List.of(type), Set.of(personId), y).cells(personId).get(0);
            if (now.total().add(days).compareTo(now.used()) < 0) {
                errors.add(error("days", "BelowUsed"));   // more taken away than is left
            }
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        adjustments.saveAndFlush(new LeaveAdjustment(personId, type.getId(), y, days.setScale(1), reason));
        return personBalances(person, y);
    }

    /** The signed-in person's balances this year (none for a login without a person). */
    @Override
    @Transactional(readOnly = true)
    public List<Balance> mine() {
        UUID me = CurrentUser.require().personId();
        if (me == null) {
            return List.of();
        }
        List<LeaveType> active = activeTypes();
        List<LeaveViews.Cell> cells = balances(active, Set.of(me), today().getYear()).cells(me);
        List<Balance> out = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            LeaveType t = active.get(i);
            LeaveViews.Cell cell = cells.get(i);
            out.add(new Balance(t.getId(), t.getCode(), mapper.name(t.getName()), t.getColor(), t.isHalfDays(), cell.total(), cell.used(), cell.left(),
                    cell.pending()));
        }
        return out;
    }

    /** GET /app/leave/balances: 404 for a login without a person. */
    @Transactional(readOnly = true)
    public LeaveViews.Mine mineView() {
        if (CurrentUser.require().personId() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return new LeaveViews.Mine(today().getYear(), mine());
    }

    // ------------------------------------------------------------------ balances

    /** Days per person and type (adjusted, used, pending). */
    private record PerType(Map<UUID, Map<UUID, BigDecimal>> days) {

        BigDecimal of(UUID personId, UUID typeId) {
            return days.getOrDefault(personId, Map.of()).getOrDefault(typeId, BigDecimal.ZERO.setScale(1));
        }
    }

    /** Start dates, adjustments and requests of some people in a year, for some types. */
    private record Balances(List<LeaveType> types, int year, Map<UUID, LocalDate> starts, PerType adjusted, PerType used, PerType pending) {

        /** The person's cells, in the order of {@code types}. */
        List<LeaveViews.Cell> cells(UUID personId) {
            return types.stream().map(t -> {
                BigDecimal entitled = Entitlements.of(t.getDaysPerYear(), t.isHalfDays(), starts.get(personId), year);
                BigDecimal plus = adjusted.of(personId, t.getId());
                BigDecimal total = entitled.add(plus);
                BigDecimal taken = used.of(personId, t.getId());
                return new LeaveViews.Cell(t.getId(), entitled, plus, total, taken, total.subtract(taken), pending.of(personId, t.getId()));
            }).toList();
        }
    }

    private static <T> PerType sum(List<T> rows, java.util.function.Function<T, UUID> person, java.util.function.Function<T, UUID> type,
            java.util.function.Function<T, BigDecimal> days) {
        return new PerType(rows.stream().collect(Collectors.groupingBy(person,
                Collectors.groupingBy(type, Collectors.reducing(BigDecimal.ZERO.setScale(1), days, BigDecimal::add)))));
    }

    private Balances balances(List<LeaveType> forTypes, Collection<UUID> personIds, int year) {
        PerType none = new PerType(Map.of());
        if (personIds.isEmpty()) {
            return new Balances(forTypes, year, Map.of(), none, none, none);
        }
        List<LeaveRequest> sent = requests.findByPersonIdInAndStatusInAndStartDateBetween(personIds,
                List.of(LeaveRequest.Status.APPROVED, LeaveRequest.Status.PENDING), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        return new Balances(forTypes, year, people.startDates(personIds),
                sum(adjustments.findByPersonIdInAndYear(personIds, year), LeaveAdjustment::getPersonId, LeaveAdjustment::getLeaveTypeId,
                        LeaveAdjustment::getDays),
                sum(sent.stream().filter(r -> r.getStatus() == LeaveRequest.Status.APPROVED).toList(), LeaveRequest::getPersonId,
                        LeaveRequest::getLeaveTypeId, LeaveRequest::getDays),
                sum(sent.stream().filter(LeaveRequest::open).toList(), LeaveRequest::getPersonId, LeaveRequest::getLeaveTypeId,
                        LeaveRequest::getDays));
    }

    /** One person's cell of a type in a year (for a new request: what may still be taken). */
    @Transactional(readOnly = true)
    public LeaveViews.Cell cell(UUID personId, LeaveType type, int year) {
        return balances(List.of(type), Set.of(personId), year).cells(personId).get(0);
    }

    private LeaveViews.PersonBalances personBalances(PeopleQueries.PersonCard p, int year) {
        List<LeaveType> active = activeTypes();
        Balances balances = balances(active, Set.of(p.id()), year);
        List<LeaveAdjustment> history = adjustments.findByPersonIdAndYearOrderByCreatedAtDesc(p.id(), year);
        Set<UUID> by = history.stream().map(LeaveAdjustment::getCreatedBy).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = by.isEmpty() ? Map.of() : accountNames.names(by);
        return new LeaveViews.PersonBalances(year, active.stream().map(mapper::type).toList(),
                new LeaveViews.Row(person(p, balances.starts().get(p.id())), balances.cells(p.id())),
                history.stream().map(a -> new LeaveViews.Adjustment(a.getId(), a.getLeaveTypeId(), a.getDays(), a.getReason(), a.getCreatedAt(),
                        a.getCreatedBy(), a.getCreatedBy() == null ? null : names.get(a.getCreatedBy()))).toList());
    }

    // ------------------------------------------------------------------ helpers

    private List<LeaveType> activeTypes() {
        return types.findBySchoolIdOrderByPositionAscNameEnAsc(schoolContext.currentSchoolId());   // the "active only" filter is on
    }

    /** An active person the caller may see, else 404. */
    private PeopleQueries.PersonCard visibleActive(UUID personId) {
        PeopleQueries.VisiblePerson v = people.visible(personId).filter(PeopleQueries.VisiblePerson::active)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        PeopleQueries.PersonLabel label = people.labels(List.of(personId)).get(personId);
        return new PeopleQueries.PersonCard(v.id(), label.staffId(), label.name(), v.role(), v.departmentId());
    }

    private static LeaveViews.Person person(PeopleQueries.PersonCard p, LocalDate start) {
        return new LeaveViews.Person(p.id(), p.staffId(), p.name(), p.role(), p.departmentId(), start);
    }

    private int year(Integer year) {
        if (year != null && (year < 2000 || year > 2100)) {
            throw validation(List.of(error("year", "Range")));
        }
        return year == null ? today().getYear() : year;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
