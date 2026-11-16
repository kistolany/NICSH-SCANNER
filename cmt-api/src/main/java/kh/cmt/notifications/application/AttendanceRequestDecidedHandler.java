package kh.cmt.notifications.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kh.cmt.attendance.api.AttendanceRequestDecided;
import kh.cmt.shared.events.OutboxHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step 10.1: an admin's decision on a late / early reason or a correction took effect → "Late reason approved" (excused),
 * "Correction rejected: …" and so on (kind {@value MissingCheckOutHandler#KIND}).
 */
@Component
@RequiredArgsConstructor
class AttendanceRequestDecidedHandler implements OutboxHandler<AttendanceRequestDecided> {

    private final Notifier notifier;

    @Override
    public String type() {
        return AttendanceRequestDecided.TYPE;
    }

    @Override
    public Class<AttendanceRequestDecided> payloadType() {
        return AttendanceRequestDecided.class;
    }

    @Override
    public void handle(AttendanceRequestDecided e) {
        Map<String, Object> p = new HashMap<>();
        p.put("date", e.date().toString());
        if (e.shiftName() != null) {
            p.put("shift", e.shiftName());
        }
        if (e.minutes() != null) {
            p.put("minutes", e.minutes());
        }
        if (e.field() != null) {
            p.put("field", e.field());
            p.put("time", e.time());
        }
        if (e.comment() != null) {
            p.put("comment", e.comment());
        }
        notifier.notify(List.of(e.personId()), MissingCheckOutHandler.KIND, e.request() + "_" + e.decision(), p);
    }
}
