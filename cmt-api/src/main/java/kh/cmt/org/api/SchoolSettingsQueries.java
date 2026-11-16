package kh.cmt.org.api;

import java.time.ZoneId;

/**
 * What other modules (attendance, schedule, notifications, reports) read from the school settings. Values are
 * those of the caller's school. Read them when needed; do not keep them, they can change.
 */
public interface SchoolSettingsQueries {

    SchoolInfo school();

    AttendanceRules attendanceRules();

    NotificationSettings notifications();

    AppearanceSettings appearance();

    /** The school timezone: use it to decide which calendar day a moment belongs to. */
    ZoneId zone();
}
