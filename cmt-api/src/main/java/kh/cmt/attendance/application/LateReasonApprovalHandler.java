package kh.cmt.attendance.application;

import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.events.Outbox;
import org.springframework.stereotype.Component;

/** LATE_REASON: approved → the late minutes are excused. */
@Component
class LateReasonApprovalHandler extends ReasonApprovalHandler {

    LateReasonApprovalHandler(AttendanceRecordRepository records, DailySummaries summaries, Outbox outbox) {
        super(records, summaries, outbox);
    }

    @Override
    boolean early() {
        return false;
    }
}
