package kh.cmt.leave.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.approvals.api.ApprovalFacts;
import kh.cmt.approvals.api.ApprovalHandler;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.leave.api.LeaveApproved;
import kh.cmt.leave.api.LeaveDecided;
import kh.cmt.leave.domain.LeaveRequest;
import kh.cmt.leave.domain.LeaveType;
import kh.cmt.leave.infrastructure.LeaveRequestRepository;
import kh.cmt.leave.infrastructure.LeaveTypeRepository;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Leave requests decided in the approvals inbox (the architecture's LeaveHandler): once the decision takes effect the
 * request follows it; the person is told ({@link LeaveDecided}); approved days count as used and become leave — attendance
 * turns absences already stored for them into leave ({@link LeaveApproved}). The detail sheet shows the balance of the
 * type now and after the request.
 */
@Component
@RequiredArgsConstructor
class LeaveApprovalHandler implements ApprovalHandler, ApprovalFacts {

    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final LeaveBalanceService balances;
    private final LeaveMapper mapper;
    private final Outbox outbox;

    @Override
    public Approvals.Type type() {
        return Approvals.Type.LEAVE;
    }

    @Override
    public void check(Decided d) {
        open(d.subjectId());
    }

    @Override
    public void decided(Decided d) {
        LeaveRequest r = open(d.subjectId());
        boolean approved = d.decision() == Approvals.Status.APPROVED;
        r.close(approved ? LeaveRequest.Status.APPROVED : LeaveRequest.Status.REJECTED, d.by(), d.at(), d.comment());
        requests.saveAndFlush(r);
        LeaveType type = types.findById(r.getLeaveTypeId()).orElseThrow();
        outbox.add(LeaveDecided.TYPE, new LeaveDecided(r.getId(), r.getPersonId(), r.getStatus().name(), mapper.name(type.getName()),
                r.getStartDate(), r.getEndDate(), r.getHalf() == null ? null : r.getHalf().name(), r.getDays(), d.comment()));
        if (approved) {
            outbox.add(LeaveApproved.TYPE, new LeaveApproved(r.getId(), r.getPersonId(), r.getWorkingDates().stream().map(LocalDate::parse).toList()));
        }
    }

    @Override
    public double leaveDays(Map<String, Object> details) {
        Object days = details.get("days");
        return days == null ? 0 : Double.parseDouble(days.toString());
    }

    /** A leave request's type: {@code total}, {@code left} now (approved leave taken off), {@code pending}, {@code leftAfter} = left − it. */
    @Override
    public Map<String, Object> facts(Approvals.Type type, UUID personId, UUID subjectId, Map<String, Object> details) {
        LeaveRequest r = type == Approvals.Type.LEAVE ? requests.findById(subjectId).orElse(null) : null;
        LeaveType leaveType = r == null ? null : types.findById(r.getLeaveTypeId()).orElse(null);
        if (leaveType == null) {
            return Map.of();
        }
        LeaveViews.Cell c = balances.cell(personId, leaveType, r.getStartDate().getYear());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("year", r.getStartDate().getYear());
        facts.put("total", c.total());
        facts.put("used", c.used());
        facts.put("left", c.left());
        facts.put("pending", c.pending());
        facts.put("leftAfter", r.open() ? c.left().subtract(r.getDays()) : c.left());
        return facts;
    }

    /** The request, still waiting (cancelled meanwhile → {@code ALREADY_DECIDED}). */
    private LeaveRequest open(UUID id) {
        LeaveRequest r = requests.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!r.open()) {
            throw new ApiException(ErrorCode.ALREADY_DECIDED).with("decision", r.getStatus().name());
        }
        return r;
    }
}
