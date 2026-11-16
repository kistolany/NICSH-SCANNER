package kh.cmt.org.api;

/** Everything the mobile app may read about the school settings (GET /api/v1/app/settings/public). */
public record PublicSettings(SchoolInfo school, AttendanceRules attendance, NotificationSettings notifications,
        AppearanceSettings appearance) {
}
