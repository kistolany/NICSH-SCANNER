package kh.cmt.leave.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.files.api.FileRef;
import kh.cmt.files.api.Files;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.leave.domain.LeaveRequest;
import kh.cmt.leave.domain.LeaveType;
import kh.cmt.leave.infrastructure.LeaveRequestRepository;
import kh.cmt.leave.infrastructure.LeaveTypeRepository;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff app's leave requests (step 9.2; the school's rules of 2026-10-26): whole days from a start to an end date
 * (one calendar year) or half a day (AM | PM of one date), counted from the person's schedule ({@link LeaveDays}); at
 * most what is left of the type this year minus the requests still waiting ({@code NOT_ENOUGH_BALANCE}); not over
 * another waiting or approved request ({@code LEAVE_OVERLAP}); starting at most {@value #DAYS_BACK} days ago; an
 * attachment when the type asks for one. Each request is sent to the approvals inbox (type LEAVE); the person may take
 * it back while it waits. Starting in less than {@value #SHORT_NOTICE_DAYS} days is "short notice" for the admin.
 */
@Service
@Transactional
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LeaveRequestService {

    static final String SUBJECT = "LEAVE_REQUEST";
    static final int DAYS_BACK = 7;
    static final int DAYS_AHEAD = 366;
    static final int SHORT_NOTICE_DAYS = 3;
    static final int REASON_MAX = 300;

    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final LeaveBalanceService balances;
    private final DaySchedules schedules;
    private final Approvals approvals;
    private final Files files;
    private final AccountNames accountNames;
    private final SchoolContext schoolContext;
    private final LeaveMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LeaveViews.Preview preview(LeaveCommands.Request c) {
        UUID me = me();
        Input in = read(c, false);
        Plan plan = plan(me, in);
        return new LeaveViews.Preview(in.type().getId(), in.start(), in.end(), in.half() == null ? null : in.half().name(), plan.count().days(),
                plan.count().dates(), plan.free(), plan.free().subtract(plan.count().days()), plan.enough(),
                plan.overlap().map(LeaveRequest::getId).orElse(null), plan.shortNotice(), plan.attachmentRequired());
    }

    public LeaveViews.Request request(LeaveCommands.Request c) {
        UUID me = me();
        Input in = read(c, true);
        Plan plan = plan(me, in);
        if (plan.count().none()) {
            throw new ApiException(ErrorCode.NO_WORKING_DAYS);
        }
        if (plan.overlap().isPresent()) {
            throw new ApiException(ErrorCode.LEAVE_OVERLAP).with("requestId", plan.overlap().get().getId());
        }
        if (!plan.enough()) {
            throw new ApiException(ErrorCode.NOT_ENOUGH_BALANCE).with("left", plan.free()).with("days", plan.count().days());
        }
        if (plan.attachmentRequired() && c.attachmentFileId() == null) {
            throw validation(List.of(error("attachmentFileId", "Required")));
        }
        if (c.attachmentFileId() != null) {
            files.attach(c.attachmentFileId(), FilePurpose.ATTACHMENT);
        }
        LeaveRequest saved = requests.saveAndFlush(new LeaveRequest(me, in.type().getId(), in.start(), in.end(), in.half(), plan.count().days(),
                plan.count().dates(), in.reason(), c.attachmentFileId(), plan.shortNotice()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("typeId", in.type().getId().toString());
        details.put("type", in.type().getCode());
        details.put("typeName", Map.of("km", nz(in.type().getName().getKm()), "en", nz(in.type().getName().getEn()), "ar", nz(in.type().getName().getAr())));
        details.put("color", in.type().getColor());
        details.put("startDate", in.start().toString());
        details.put("endDate", in.end().toString());
        details.put("half", in.half() == null ? null : in.half().name());
        details.put("days", plan.count().days());
        details.put("workingDates", plan.count().dates().stream().map(LocalDate::toString).toList());
        details.put("shortNotice", plan.shortNotice());
        details.put("leftBefore", plan.free());
        details.put("leftAfter", plan.free().subtract(plan.count().days()));
        details.put("total", plan.total());
        if (c.attachmentFileId() != null) {
            details.put("attachmentFileId", c.attachmentFileId().toString());
        }
        approvals.request(new Approvals.Request(Approvals.Type.LEAVE, SUBJECT, saved.getId(), me, in.reason(), details));
        return view(saved, Map.of(in.type().getId(), in.type()), c.attachmentFileId() == null ? Map.of() : files.find(Set.of(c.attachmentFileId())));
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<LeaveViews.Request> mine() {
        List<LeaveRequest> list = requests.findByPersonIdOrderByStartDateDescCreatedAtDesc(me());
        Map<UUID, LeaveType> byId = typesOf(list);
        Map<UUID, FileRef> attached = files.find(list.stream().map(LeaveRequest::getAttachmentFileId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(r -> view(r, byId, attached)).toList();
    }

    @Transactional(readOnly = true)
    public LeaveViews.RequestDetail one(UUID id) {
        LeaveRequest r = own(id);
        Map<UUID, FileRef> attached = r.getAttachmentFileId() == null ? Map.of() : files.find(Set.of(r.getAttachmentFileId()));
        List<LeaveViews.Event> timeline = new ArrayList<>();
        timeline.add(new LeaveViews.Event("SENT", r.getCreatedAt(), null, null));
        if (!r.open()) {
            String by = r.getStatus() == LeaveRequest.Status.CANCELLED || r.getDecidedBy() == null ? null
                    : accountNames.names(Set.of(r.getDecidedBy())).get(r.getDecidedBy());
            timeline.add(new LeaveViews.Event(r.getStatus().name(), r.getDecidedAt(), by, r.getComment()));
        }
        return new LeaveViews.RequestDetail(view(r, typesOf(List.of(r)), attached), timeline);
    }

    /** Taken back while it waits (the inbox's request too); decided → {@code ALREADY_DECIDED}. */
    public void cancel(UUID id) {
        LeaveRequest r = own(id);
        if (!r.open()) {
            throw new ApiException(ErrorCode.ALREADY_DECIDED).with("decision", r.getStatus().name());
        }
        r.close(LeaveRequest.Status.CANCELLED, CurrentUser.require().userId(), clock.instant(), null);
        approvals.cancel(Approvals.Type.LEAVE, r.getId());
    }

    // ------------------------------------------------------------------ the rules

    private record Input(LeaveType type, LocalDate start, LocalDate end, LeaveRequest.Half half, String reason) {
    }

    /**
     * {@code free} = left − waiting requests of the type in the year; {@code overlap} a waiting or approved request
     * already covering some of the time.
     */
    private record Plan(LeaveDays.Count count, BigDecimal total, BigDecimal free, boolean enough, Optional<LeaveRequest> overlap,
            boolean shortNotice, boolean attachmentRequired) {
    }

    private Plan plan(UUID me, Input in) {
        LeaveDays.Count count = LeaveDays.of(schedules.forPerson(me, in.start(), in.end()), in.half() != null);
        LeaveViews.Cell cell = balances.cell(me, in.type(), in.start().getYear());
        BigDecimal free = cell.left().subtract(cell.pending());
        Optional<LeaveRequest> overlap = requests.findByPersonIdInAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(Set.of(me),
                Set.of(LeaveRequest.Status.PENDING, LeaveRequest.Status.APPROVED), in.end(), in.start()).stream()
                .filter(r -> r.overlaps(in.start(), in.end(), in.half())).findFirst();
        boolean attachment = switch (in.type().getAttachment()) {
            case ALWAYS -> true;
            case OVER_ONE_DAY -> count.days().compareTo(BigDecimal.ONE) > 0;
            case NONE -> false;
        };
        return new Plan(count, cell.total(), free, free.compareTo(count.days()) >= 0, overlap, in.start().isBefore(today().plusDays(SHORT_NOTICE_DAYS)), attachment);
    }

    /** The request's own problems at once ({@code reason} only when sending). */
    private Input read(LeaveCommands.Request c, boolean sending) {
        List<Map<String, String>> errors = new ArrayList<>();
        LeaveType type = null;
        if (c.typeId() == null) {
            errors.add(error("typeId", "NotNull"));
        } else {
            UUID school = schoolContext.currentSchoolId();
            type = types.findById(c.typeId()).filter(t -> t.getSchoolId().equals(school) && !t.isInactive()).orElse(null);
            if (type == null) {
                errors.add(error("typeId", "Unknown"));
            }
        }
        LocalDate start = date(c.startDate(), "startDate", true, errors);
        LocalDate end = c.endDate() == null || c.endDate().isBlank() ? start : date(c.endDate(), "endDate", true, errors);
        LocalDate today = today();
        if (start != null) {
            if (start.isBefore(today.minusDays(DAYS_BACK))) {
                errors.add(error("startDate", "TooFarBack"));
            } else if (start.isAfter(today.plusDays(DAYS_AHEAD))) {
                errors.add(error("startDate", "TooFarAhead"));
            }
        }
        if (start != null && end != null) {
            if (end.isBefore(start)) {
                errors.add(error("endDate", "BeforeStart"));
            } else if (end.getYear() != start.getYear()) {
                errors.add(error("endDate", "SameYear"));   // a balance is per calendar year: send one request per year
            }
        }
        LeaveRequest.Half half = null;
        if (c.half() != null && !c.half().isBlank()) {
            try {
                half = LeaveRequest.Half.valueOf(c.half().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException wrong) {
                errors.add(error("half", "OneOf"));
            }
            if (half != null && start != null && end != null && !end.equals(start)) {
                errors.add(error("half", "OneDay"));
            }
            if (half != null && type != null && !type.isHalfDays()) {
                errors.add(error("half", "NotAllowed"));   // a type taken by whole days only
            }
        }
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (sending && reason.isEmpty()) {
            errors.add(error("reason", "NotBlank"));
        } else if (reason.length() > REASON_MAX) {
            errors.add(error("reason", "Size"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return new Input(type, start, end, half, reason);
    }

    private static LocalDate date(String value, String field, boolean required, List<Map<String, String>> errors) {
        if (value == null || value.isBlank()) {
            if (required) {
                errors.add(error(field, "NotNull"));
            }
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException wrong) {
            errors.add(error(field, "Pattern"));
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private LeaveViews.Request view(LeaveRequest r, Map<UUID, LeaveType> byId, Map<UUID, FileRef> attached) {
        LeaveType t = byId.get(r.getLeaveTypeId());
        FileRef f = r.getAttachmentFileId() == null ? null : attached.get(r.getAttachmentFileId());
        return new LeaveViews.Request(r.getId(), r.getLeaveTypeId(), t == null ? null : t.getCode(), t == null ? null : mapper.name(t.getName()),
                t == null ? null : t.getColor(), r.getStartDate(), r.getEndDate(), r.getHalf() == null ? null : r.getHalf().name(), r.getDays(),
                r.getWorkingDates().stream().map(LocalDate::parse).toList(), r.getReason(),
                f == null ? null : new LeaveViews.Attachment(f.id(), f.name(), f.mime(), f.size()), r.isShortNotice(), r.getStatus().name(),
                r.getCreatedAt(), r.getDecidedAt(), r.getComment());
    }

    /** Types of the requests, inactive ones too (old requests keep their type's name). */
    private Map<UUID, LeaveType> typesOf(List<LeaveRequest> list) {
        return types.findAllById(list.stream().map(LeaveRequest::getLeaveTypeId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(LeaveType::getId, t -> t));
    }

    private LeaveRequest own(UUID id) {
        UUID me = me();
        return requests.findById(id).filter(r -> r.getPersonId().equals(me)).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** The signed-in person (404 for a login without one). */
    private static UUID me() {
        UUID me = CurrentUser.require().personId();
        if (me == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return me;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
