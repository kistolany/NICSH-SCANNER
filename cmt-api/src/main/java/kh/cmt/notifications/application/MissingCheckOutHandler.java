package kh.cmt.notifications.application;

import java.util.List;
import java.util.Map;
import kh.cmt.attendance.api.MissingCheckOut;
import kh.cmt.shared.events.OutboxHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step 7.8: the end of the day found a check-in without a check-out → "You forgot to check out" (kind
 * {@value #KIND}), with the way out: a correction request in the app.
 */
@Component
@RequiredArgsConstructor
class MissingCheckOutHandler implements OutboxHandler<MissingCheckOut> {

    static final String KIND = "ATTENDANCE";

    private final Notifier notifier;

    @Override
    public String type() {
        return MissingCheckOut.TYPE;
    }

    @Override
    public Class<MissingCheckOut> payloadType() {
        return MissingCheckOut.class;
    }

    @Override
    public void handle(MissingCheckOut e) {
        notifier.notify(List.of(e.personId()), KIND, NotificationTexts.MISSING_CHECK_OUT,
                Map.of("date", e.date().toString(), "times", e.times()));
    }
}
