package kh.cmt.notifications.api;

/** The signed-in app user's newest notifications, for the app's Home (step 7.9). */
public interface NotificationFeed {

    /** The newest {@code count} (1 – 100), text in the reader's language (as GET /app/notifications), and how many are unread. */
    MyNotifications latest(int count, String acceptLanguage);
}
