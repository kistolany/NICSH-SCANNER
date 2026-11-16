package kh.cmt.approvals.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kh.cmt.approvals.api.ApprovalFacts;
import kh.cmt.approvals.api.ApprovalHandler;
import kh.cmt.approvals.api.ApprovalSource;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.domain.Approval;
import kh.cmt.approvals.infrastructure.ApprovalRepository;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.iam.api.ApprovalRequest;
import kh.cmt.iam.api.ApprovalRouting;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one approvals inbox (step 10.1; Approvals page, admin app): every type of request, waiting ones oldest first, or the
 * latest decided ones; counts per type and per group (the chips, the menu badge, "Needs your action").
 * <ul>
 * <li>Approve / reject (a comment is required to reject): the decision shows at once and takes effect
 * {@link #UNDO_WINDOW} later — then the module's {@link ApprovalHandler} applies it ({@link ApprovalEffects}). Undo
 * before that puts the request back, with nothing to reverse. Device changes ({@link ApprovalSource}) are decided at once,
 * without Undo.</li>
 * <li>The approver's role must cover the request (Roles › Approval limits: leave length, old corrections, own requests),
 * else {@code APPROVAL_LIMIT} with the role it moves to ({@code escalateTo}); each waiting item says so beforehand
 * ({@code canDecide}). An admin of the mobile admin app decides with the limits of their linked web user's role, else of the
 * school's default role.</li>
 * <li>Approve all: Late / Early reasons only; each one is decided on its own, refusals are listed.</li>
 * </ul>
 * Record scope applies. Permissions: Approvals view / approve.
 */
@Service
@Transactional
public class ApprovalInboxService {

    static final int DONE_SHOWN = 100;
    static final int COMMENT_MAX = 500;
    static final int BULK_MAX = 200;
    static final Duration UNDO_WINDOW = Duration.ofSeconds(10);

    /** The chips of the inbox: one or two types each. */
    enum Group {
        leave(Approvals.Type.LEAVE), correction(Approvals.Type.CORRECTION), lateEarly(Approvals.Type.LATE_REASON, Approvals.Type.EARLY_REASON),
        device(Approvals.Type.DEVICE_CHANGE);

        final Set<Approvals.Type> types;

        Group(Approvals.Type... types) {
            this.types = Set.of(types);
        }

        static Group of(Approvals.Type type) {
            return Stream.of(values()).filter(g -> g.types.contains(type)).findFirst().orElseThrow();
        }
    }

    private final ApprovalRepository approvals;
    private final Map<Approvals.Type, ApprovalHandler> handlers = new EnumMap<>(Approvals.Type.class);
    private final Map<Approvals.Type, ApprovalSource> sources = new EnumMap<>(Approvals.Type.class);
    private final List<ApprovalFacts> facts;
    private final PeopleQueries people;
    private final AccountNames accountNames;
    private final ApprovalRouting routing;
    private final SchoolContext schoolContext;
    private final Clock clock;

    ApprovalInboxService(ApprovalRepository approvals, List<ApprovalHandler> handlers, List<ApprovalSource> sources, List<ApprovalFacts> facts,
            PeopleQueries people, AccountNames accountNames, ApprovalRouting routing, SchoolContext schoolContext, Clock clock) {
        this.approvals = approvals;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
        sources.forEach(s -> this.sources.put(s.type(), s));
        this.facts = facts;
        this.people = people;
        this.accountNames = accountNames;
        this.routing = routing;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    /** {@code counts}: waiting requests per type, {@code groups} per chip, {@code total} all of them; {@code items} of the tab asked for. */
    public record Inbox(String status, Map<Approvals.Type, Long> counts, Map<String, Long> groups, long total, List<Item> items) {
    }

    /**
     * {@code canDecide}: the caller's role covers it (else {@code escalateTo} + {@code limitReason}); {@code undoUntil}:
     * decided, takes effect then (Undo until); {@code effectError}: the effect could not be applied.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(UUID id, Approvals.Type type, String group, Approvals.Status status, Person person, String reason,
            Map<String, Object> details, Instant requestedAt, Instant decidedAt, UUID decidedBy, String decidedByName, String comment,
            boolean decidable, Boolean canDecide, String escalateTo, String limitReason, Instant undoUntil, String effectError) {
    }

    /** {@code photoFileId}: open it with {@code GET /api/v1/files/{id}/url} (or {@code /view}); null when none. */
    public record Person(UUID id, String staffId, LocalizedName name, UUID photoFileId) {
    }

    /** {@code history}: the person's requests this month per type; {@code facts}: the module's ("lateThisMonth", "leftAfter"…). */
    public record Detail(Item item, Map<Approvals.Type, Long> history, Map<String, Object> facts) {
    }

    public record Bulk(List<Item> approved, List<Refused> refused) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Refused(UUID id, String code, String reason, String escalateTo) {
    }

    /** A request of the table or of a source, the same for the inbox. */
    private record Row(UUID id, Approvals.Type type, Approvals.Status status, UUID personId, String reason, Map<String, Object> details,
            Instant requestedAt, Instant decidedAt, UUID decidedBy, String comment, Instant undoUntil, String effectError) {

        static Row of(Approval a) {
            return new Row(a.getId(), a.getType(), a.getStatus(), a.getPersonId(), a.getReason(), a.getDetails(), a.getRequestedAt(),
                    a.getDecidedAt(), a.getDecidedBy(), a.getComment(), a.waiting() ? a.getEffectiveAt() : null, a.getEffectError());
        }

        static Row of(Approvals.Type type, ApprovalSource.Request r) {
            return new Row(r.id(), type, r.status(), r.personId(), r.reason(), r.details(), r.requestedAt(), r.decidedAt(), r.decidedBy(),
                    r.comment(), null, null);
        }

        Map<String, Object> detailsOrEmpty() {
            return details == null ? Map.of() : details;
        }
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.VIEW)
    public Inbox list(String status, Approvals.Type type, String group) {
        boolean done = switch (status == null ? "pending" : status) {
            case "pending" -> false;
            case "done" -> true;
            default -> throw validation("status", "OneOf");
        };
        Group g = group(group);
        UUID school = schoolContext.currentSchoolId();
        List<Row> waiting = waiting(school);
        Map<Approvals.Type, Long> counts = new EnumMap<>(Approvals.Type.class);
        Map<String, Long> groups = new LinkedHashMap<>();
        Stream.of(Group.values()).forEach(x -> groups.put(x.name(), 0L));
        waiting.forEach(r -> {
            counts.merge(r.type(), 1L, Long::sum);
            groups.merge(Group.of(r.type()).name(), 1L, Long::sum);
        });
        List<Row> shown = (done ? decided(school) : waiting).stream()
                .filter(r -> (type == null || r.type() == type) && (g == null || g.types.contains(r.type()))).toList();
        return new Inbox(done ? "done" : "pending", counts, groups, waiting.size(), items(shown));
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.VIEW)
    public Detail detail(UUID id) {
        Row r = row(id);
        Instant monthStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(clock.getZone()).toInstant();
        Map<Approvals.Type, Long> history = new EnumMap<>(Approvals.Type.class);
        for (Approvals.Type t : Approvals.Type.values()) {
            if (!sources.containsKey(t)) {
                history.put(t, approvals.countSince(r.personId(), t, monthStart));
            }
        }
        Map<String, Object> known = new LinkedHashMap<>();
        UUID subject = subjectOf(r);
        facts.forEach(f -> known.putAll(f.facts(r.type(), r.personId(), subject, r.detailsOrEmpty())));
        return new Detail(items(List.of(r)).getFirst(), history, known);
    }

    /** Waiting: the table's (scope applies) and the sources' (they filter by scope themselves), oldest first. */
    private List<Row> waiting(UUID school) {
        List<Row> rows = new ArrayList<>(approvals.findBySchoolIdAndStatusOrderByRequestedAtAsc(school, Approvals.Status.PENDING).stream()
                .map(Row::of).toList());
        sources.forEach((t, s) -> s.list(true, 500).forEach(x -> rows.add(Row.of(t, x))));
        rows.sort(Comparator.comparing(Row::requestedAt));
        return rows;
    }

    /** The latest decided ones, newest first. */
    private List<Row> decided(UUID school) {
        List<Row> rows = new ArrayList<>(approvals.findBySchoolIdAndStatusInOrderByDecidedAtDesc(school,
                Set.of(Approvals.Status.APPROVED, Approvals.Status.REJECTED), PageRequest.of(0, DONE_SHOWN)).stream().map(Row::of).toList());
        sources.forEach((t, s) -> s.list(false, DONE_SHOWN).forEach(x -> rows.add(Row.of(t, x))));
        rows.sort(Comparator.comparing(Row::decidedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.size() > DONE_SHOWN ? rows.subList(0, DONE_SHOWN) : rows;
    }

    // ------------------------------------------------------------------ deciding

    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.APPROVE)
    public Item approve(UUID id, String comment) {
        return decide(id, Approvals.Status.APPROVED, comment == null || comment.isBlank() ? null : comment.trim());
    }

    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.APPROVE)
    public Item reject(UUID id, String comment) {
        String c = comment == null ? "" : comment.trim();
        if (c.isEmpty() || c.length() > COMMENT_MAX) {
            throw validation("comment", c.isEmpty() ? "NotBlank" : "Size");
        }
        return decide(id, Approvals.Status.REJECTED, c);
    }

    /**
     * "Approve all" of Late / Early: each request on its own (one that cannot be decided is listed in {@code refused}
     * with its code, the others are approved). Other types → {@code VALIDATION_ERROR} (rule {@code LateEarlyOnly}).
     */
    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.APPROVE)
    public Bulk approveAll(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BULK_MAX) {
            throw validation("ids", ids == null || ids.isEmpty() ? "NotEmpty" : "Size");
        }
        List<Item> approved = new ArrayList<>();
        List<Refused> refused = new ArrayList<>();
        for (UUID id : ids.stream().filter(Objects::nonNull).distinct().toList()) {
            Optional<Approval> a = table(id);
            if (a.isPresent() && !Group.lateEarly.types.contains(a.get().getType())) {
                throw validation("ids", "LateEarlyOnly");
            }
            try {
                approved.add(decide(id, Approvals.Status.APPROVED, null));
            } catch (ApiException e) {
                refused.add(new Refused(id, e.code().name(), string(e.properties().get("reason")), string(e.properties().get("escalateTo"))));
            }
        }
        return new Bulk(approved, refused);
    }

    /** Takes a decision back while it has not taken effect: the request waits again. Too late → {@code CONFLICT} TOO_LATE. */
    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.APPROVE)
    public Item undo(UUID id) {
        Approval a = table(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (a.open()) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "NOT_DECIDED");
        }
        if (!a.waiting()) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "TOO_LATE");
        }
        a.undo();
        approvals.saveAndFlush(a);
        return items(List.of(Row.of(a))).getFirst();
    }

    private Item decide(UUID id, Approvals.Status decision, String comment) {
        if (comment != null && comment.length() > COMMENT_MAX) {
            throw validation("comment", "Size");
        }
        Optional<Approval> stored = table(id);
        if (stored.isEmpty()) {
            return decideInSource(id, decision, comment);
        }
        Approval a = stored.get();
        if (!a.open()) {
            throw new ApiException(ErrorCode.ALREADY_DECIDED).with("decision", a.getStatus().name());
        }
        ApprovalHandler handler = handlers.get(a.getType());
        if (handler == null) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "NOT_YET");   // no module decides this type
        }
        CurrentUser me = CurrentUser.require();
        Row row = Row.of(a);
        mayDecide(me, row);
        Instant now = clock.instant();
        handler.check(new ApprovalHandler.Decided(a.getId(), a.getSubjectId(), a.getPersonId(), decision, me.userId(), now, comment,
                row.detailsOrEmpty()));
        a.decide(decision, me.userId(), now, comment, now.plus(UNDO_WINDOW));
        approvals.saveAndFlush(a);
        return items(List.of(Row.of(a))).getFirst();
    }

    private Item decideInSource(UUID id, Approvals.Status decision, String comment) {
        for (Map.Entry<Approvals.Type, ApprovalSource> e : sources.entrySet()) {
            Optional<ApprovalSource.Request> found = e.getValue().find(id);
            if (found.isEmpty()) {
                continue;
            }
            Row row = Row.of(e.getKey(), found.get());
            if (row.status() != Approvals.Status.PENDING) {
                throw new ApiException(ErrorCode.ALREADY_DECIDED).with("decision", row.status().name());
            }
            mayDecide(CurrentUser.require(), row);
            if (decision == Approvals.Status.APPROVED) {
                e.getValue().approve(id, comment);
            } else {
                e.getValue().reject(id, comment);
            }
            return items(List.of(Row.of(e.getKey(), e.getValue().find(id).orElseThrow()))).getFirst();
        }
        throw new ApiException(ErrorCode.NOT_FOUND);
    }

    private void mayDecide(CurrentUser me, Row r) {
        ApprovalRouting.Decision may = routing.check(schoolContext.currentSchoolId(), approverRole(me), me.personId(), asked(r));
        if (!may.allowed()) {
            throw new ApiException(ErrorCode.APPROVAL_LIMIT).with("reason", may.reason() == null ? null : may.reason().name())
                    .with("escalateTo", may.escalateTo());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The web user's role; for an admin of the mobile admin app, the role {@link ApprovalRouting#appAdminRole} gives. */
    private String approverRole(CurrentUser me) {
        return me.isApp() ? routing.appAdminRole(schoolContext.currentSchoolId(), me.personId()) : me.role();
    }

    /** What the approval limits look at. */
    private ApprovalRequest asked(Row r) {
        ApprovalHandler handler = handlers.get(r.type());
        if (handler == null) {
            return ApprovalRequest.other(r.personId());
        }
        double days = handler.leaveDays(r.detailsOrEmpty());
        if (days > 0) {
            return ApprovalRequest.leave((int) Math.ceil(days), r.personId());
        }
        LocalDate day = handler.correctionDay(r.detailsOrEmpty());
        return day != null ? ApprovalRequest.correction(day, r.personId()) : ApprovalRequest.other(r.personId());
    }

    private Optional<Approval> table(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return approvals.findById(id).filter(x -> x.getSchoolId().equals(school));
    }

    private Row row(UUID id) {
        Optional<Approval> a = table(id);
        if (a.isPresent()) {
            return Row.of(a.get());
        }
        return sources.entrySet().stream().flatMap(e -> e.getValue().find(id).map(x -> Row.of(e.getKey(), x)).stream()).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private UUID subjectOf(Row r) {
        return table(r.id()).map(Approval::getSubjectId).orElse(r.id());
    }

    private List<Item> items(List<Row> rows) {
        Map<UUID, PeopleQueries.PersonLabel> who = people.labels(rows.stream().map(Row::personId).collect(Collectors.toSet()));
        Set<UUID> deciders = rows.stream().map(Row::decidedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = deciders.isEmpty() ? Map.of() : accountNames.names(deciders);
        Function<Row, Person> person = r -> Stream.ofNullable(who.get(r.personId())).map(l -> new Person(l.id(), l.staffId(), l.name(), l.photoFileId()))
                .findFirst().orElse(new Person(r.personId(), null, null, null));
        Optional<CurrentUser> me = CurrentUser.current();
        UUID school = schoolContext.currentSchoolId();
        Map<ApprovalRequest, ApprovalRouting.Decision> checked = new HashMap<>();
        boolean anyWaiting = rows.stream().anyMatch(r -> r.status() == Approvals.Status.PENDING);
        String role = me.isPresent() && anyWaiting ? approverRole(me.get()) : null;
        return rows.stream().map(r -> {
            ApprovalRouting.Decision may = r.status() != Approvals.Status.PENDING || me.isEmpty() ? null
                    : checked.computeIfAbsent(asked(r), q -> routing.check(school, role, me.get().personId(), q));
            return new Item(r.id(), r.type(), Group.of(r.type()).name(), r.status(), person.apply(r), r.reason(), r.details(), r.requestedAt(),
                    r.decidedAt(), r.decidedBy(), r.decidedBy() == null ? null : names.get(r.decidedBy()), r.comment(),
                    handlers.containsKey(r.type()) || sources.containsKey(r.type()), may == null ? null : may.allowed(),
                    may == null ? null : may.escalateTo(), may == null || may.reason() == null ? null : may.reason().name(), r.undoUntil(),
                    r.effectError());
        }).toList();
    }

    private static Group group(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Group.valueOf(name.trim());
        } catch (IllegalArgumentException unknown) {
            throw validation("group", "OneOf");
        }
    }

    private static String string(Object o) {
        return o == null ? null : o.toString();
    }

    private static ApiException validation(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", field, "rule", rule)));
    }
}
