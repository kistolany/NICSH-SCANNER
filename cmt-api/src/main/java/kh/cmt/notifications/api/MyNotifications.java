package kh.cmt.notifications.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The app's notifications list: newest first, with the number not read yet. */
public record MyNotifications(List<Item> items, int page, int size, long total, long unread) {

    /**
     * {@code kind} what the app groups and translates (SCHEDULE…); {@code code} + {@code params} the event;
     * {@code title} / {@code body} the text in the reader's language (the same as the push).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(UUID id, String kind, String code, Map<String, Object> params, String title, String body, Instant createdAt,
            boolean read, Instant readAt) {
    }

    /** POST /app/push-token: {@code platform} android | ios; {@code locale} km | en | ar (the app's language). */
    public record PushTokenCommand(String token, String platform, String locale) {
    }
}
