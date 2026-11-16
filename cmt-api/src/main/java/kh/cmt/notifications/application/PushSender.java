package kh.cmt.notifications.application;

import java.util.Map;

/**
 * Sends one push to one phone. {@code FcmPushSender} when Firebase credentials are configured
 * ({@code cmt.push.fcm.credentials-file}), else {@code LoggingPushSender}, which only logs (no Firebase project yet).
 */
public interface PushSender {

    /** SENT; SKIPPED (pushing is off); TOKEN_GONE (the phone no longer has this token: forget it); FAILED. */
    enum Result { SENT, SKIPPED, TOKEN_GONE, FAILED }

    /** {@code data}: values the app reads when the push is tapped (notification id, kind, code). */
    record PushMessage(String token, String title, String body, Map<String, String> data) {
    }

    Result send(PushMessage message);
}
