package kh.cmt.attendance.application;

import kh.cmt.approvals.api.ApprovalHandler;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.api.AttendanceRequestDecided;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;

/**
 * A late or early reason decided in the approvals inbox (step 10.1): approved = excused — the minutes stay on the record
 * ("Late 12 min · Excused") but no longer count, so the shift is not LATE / EARLY for them; rejected = they count as before.
 * The day's summary follows, the person is told either way ({@link AttendanceRequestDecided}).
 */
abstract class ReasonApprovalHandler implements ApprovalHandler {

    private final AttendanceRecordRepository records;
    private final DailySummaries summaries;
    private final Outbox outbox;

    ReasonApprovalHandler(AttendanceRecordRepository records, DailySummaries summaries, Outbox outbox) {
        this.records = records;
        this.summaries = summaries;
        this.outbox = outbox;
    }

    /** Leaving early (else a late check-in). */
    abstract boolean early();

    @Override
    public Approvals.Type type() {
        return early() ? Approvals.Type.EARLY_REASON : Approvals.Type.LATE_REASON;
    }

    @Override
    public void check(Decided d) {
        record(d);
    }

    @Override
    public void decided(Decided d) {
        AttendanceRecord r = record(d);
        boolean excused = d.decision() == Approvals.Status.APPROVED;
        if (early()) {
            r.excuseEarly(excused);
        } else {
            r.excuseLate(excused);
        }
        records.saveAndFlush(r);
        summaries.refresh(r.getPersonId(), r.getWorkDate());
        outbox.add(AttendanceRequestDecided.TYPE, new AttendanceRequestDecided(r.getPersonId(), type().name(), d.decision().name(), r.getWorkDate(),
                r.getShiftName(), early() ? r.getEarlyMinutes() : r.getLateMinutes(), null, null, d.comment()));
    }

    private AttendanceRecord record(Decided d) {
        return records.findById(d.subjectId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
