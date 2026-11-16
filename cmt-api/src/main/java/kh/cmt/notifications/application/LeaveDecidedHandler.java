package kh.cmt.notifications.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kh.cmt.leave.api.LeaveDecided;
import kh.cmt.shared.events.OutboxHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step 9.2: an admin approved or rejected a leave request → "Leave request approved" / "… rejected" with the admin's
 * note (kind {@value #KIND}).
 */
@Component
@RequiredArgsConstructor
class LeaveDecidedHandler implements OutboxHandler<LeaveDecided> {

    static final String KIND = "LEAVE";

    private final Notifier notifier;

    @Override
    public String type() {
        return LeaveDecided.TYPE;
    }

    @Override
    public Class<LeaveDecided> payloadType() {
        return LeaveDecided.class;
    }

    @Override
    public void handle(LeaveDecided e) {
        Map<String, Object> p = new HashMap<>();
        Map<String, Object> name = new HashMap<>();
        if (e.typeName() != null) {
            name.put("km", e.typeName().km());
            name.put("en", e.typeName().en());
            name.put("ar", e.typeName().ar());
        }
        p.put("name", name);
        p.put("start", e.startDate().toString());
        p.put("end", e.endDate().toString());
        p.put("days", e.days().stripTrailingZeros().toPlainString());
        if (e.half() != null) {
            p.put("half", e.half());
        }
        if (e.comment() != null) {
            p.put("comment", e.comment());
        }
        p.put("requestId", e.requestId().toString());
        notifier.notify(List.of(e.personId()), KIND,
                "APPROVED".equals(e.status()) ? NotificationTexts.LEAVE_APPROVED : NotificationTexts.LEAVE_REJECTED, p);
    }
}
