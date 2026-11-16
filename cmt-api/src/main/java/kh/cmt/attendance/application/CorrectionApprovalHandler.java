package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.approvals.api.ApprovalHandler;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.api.AttendanceRecorded;
import kh.cmt.attendance.api.AttendanceRequestDecided;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A correction decided in the approvals inbox (step 10.1). Approved: the time asked for becomes the record's check-in or
 * check-out (minutes and status follow, "Edited by admin" with the approver and {@link #REASON}); a check-in for a shift
 * without a record makes a manual one from that day's plan. Checked when the admin approves, so a request that cannot be
 * applied is refused then: a check-out without a check-in, or times in the wrong order ({@code CONFLICT}
 * NO_CHECK_IN / TIMES_ORDER); the shift no longer planned ({@code NOT_A_WORKING_DAY}). Rejected: nothing changes. The
 * person is told either way ({@link AttendanceRequestDecided}).
 */
@Component
@RequiredArgsConstructor
class CorrectionApprovalHandler implements ApprovalHandler {

    static final String REASON = "Correction request approved";

    private final AttendanceRecordRepository records;
    private final DaySchedules schedules;
    private final ScanMapper events;
    private final Outbox outbox;
    private final Clock clock;

    /** What the request asks for (from its details). */
    private record Asked(UUID person, LocalDate date, int shiftIdx, CorrectionService.Field field, Instant time, String hhmm) {

        static Asked of(Decided d) {
            Map<String, Object> x = d.details();
            return new Asked(d.personId(), LocalDate.parse(x.get("date").toString()), Integer.parseInt(x.get("shiftIdx").toString()),
                    CorrectionService.Field.valueOf(x.get("field").toString()), Instant.parse(x.get("requestedAt").toString()),
                    String.valueOf(x.get("requestedTime")));
        }
    }

    @Override
    public Approvals.Type type() {
        return Approvals.Type.CORRECTION;
    }

    @Override
    public LocalDate correctionDay(Map<String, Object> details) {
        Object date = details.get("date");
        return date == null ? null : LocalDate.parse(date.toString());
    }

    @Override
    public void check(Decided d) {
        if (d.decision() == Approvals.Status.APPROVED) {
            apply(Asked.of(d), d.by(), false);
        }
    }

    @Override
    public void decided(Decided d) {
        Asked a = Asked.of(d);
        if (d.decision() == Approvals.Status.APPROVED) {
            apply(a, d.by(), true);
        }
        outbox.add(AttendanceRequestDecided.TYPE, new AttendanceRequestDecided(a.person(), type().name(), d.decision().name(), a.date(),
                Optional.ofNullable(d.details().get("shiftName")).map(Object::toString).orElse(null), null, a.field().name(), a.hhmm(),
                d.comment()));
    }

    /** Checks the request against the record as it is now; {@code save}: and changes it. */
    private void apply(Asked a, UUID by, boolean save) {
        Optional<AttendanceRecord> found = records.findByPersonIdAndWorkDateAndShiftIdx(a.person(), a.date(), a.shiftIdx());
        Instant now = clock.instant();
        if (found.isEmpty()) {
            if (a.field() == CorrectionService.Field.CHECK_OUT) {
                throw new ApiException(ErrorCode.CONFLICT).with("reason", "NO_CHECK_IN");
            }
            DaySchedules.ScheduledDay day = schedules.forPerson(a.person(), a.date());
            DaySchedules.Shift s = day.kind() != DaySchedules.Kind.WORK ? null
                    : day.shifts().stream().filter(x -> x.position() == a.shiftIdx() && !x.onLeave()).findFirst().orElse(null);
            if (s == null) {
                throw new ApiException(ErrorCode.NOT_A_WORKING_DAY).with("kind", day.kind().name());
            }
            if (save) {
                AttendanceRecord r = records.saveAndFlush(AttendanceRecord.manual(a.person(), a.date(),
                        new AttendanceRecord.Plan(s.position(), s.name(), s.start(), s.end(), s.graceMinutes(), s.earlyCheckOutNeedsReason()),
                        a.time(), null, by, REASON, now, clock.getZone()));
                outbox.add(AttendanceRecorded.TYPE, events.changed(r, "CORRECTED", now));
            }
            return;
        }
        AttendanceRecord r = found.get();
        Instant in = a.field() == CorrectionService.Field.CHECK_IN ? a.time() : r.getInAt();
        Instant out = a.field() == CorrectionService.Field.CHECK_OUT ? a.time() : r.getOutAt();
        if (in == null) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "NO_CHECK_IN");
        }
        if (out != null && !out.isAfter(in)) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "TIMES_ORDER");
        }
        if (save) {
            r.edit(in, out, by, REASON, now, clock.getZone());
            records.saveAndFlush(r);
            outbox.add(AttendanceRecorded.TYPE, events.changed(r, "CORRECTED", now));
        }
    }
}
