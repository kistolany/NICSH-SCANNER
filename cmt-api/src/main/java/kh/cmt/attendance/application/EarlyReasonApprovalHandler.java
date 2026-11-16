package kh.cmt.attendance.application;

import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.events.Outbox;
import org.springframework.stereotype.Component;

/** EARLY_REASON: approved → the minutes left early are excused. */
@Component
class EarlyReasonApprovalHandler extends ReasonApprovalHandler {

    EarlyReasonApprovalHandler(AttendanceRecordRepository records, DailySummaries summaries, Outbox outbox) {
        super(records, summaries, outbox);
    }

    @Override
    boolean early() {
        return true;
    }
}
