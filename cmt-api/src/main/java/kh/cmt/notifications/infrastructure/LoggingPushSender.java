package kh.cmt.notifications.infrastructure;

import kh.cmt.notifications.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used while no Firebase project is configured: logs what would be pushed (never the whole token). */
class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public Result send(PushMessage message) {
        String token = message.token();
        log.info("Push not sent (FCM is not configured) to …{}: {} — {}", token.substring(Math.max(0, token.length() - 6)),
                message.title(), message.body());
        return Result.SKIPPED;
    }
}
