package kh.cmt.attendance.application;

import kh.cmt.attendance.api.AttendanceRecorded;
import kh.cmt.shared.events.OutboxHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A check-in, check-out, edit or manual entry was recorded: that person's day in {@code attendance_daily_summary} is
 * written again (step 7.8), so reports see changes made after the day was closed too.
 */
@Component
@RequiredArgsConstructor
class AttendanceRecordedHandler implements OutboxHandler<AttendanceRecorded> {

    private final DailySummaries summaries;

    @Override
    public String type() {
        return AttendanceRecorded.TYPE;
    }

    @Override
    public Class<AttendanceRecorded> payloadType() {
        return AttendanceRecorded.class;
    }

    @Override
    public void handle(AttendanceRecorded e) {
        summaries.refresh(e.personId(), e.workDate());
    }
}
