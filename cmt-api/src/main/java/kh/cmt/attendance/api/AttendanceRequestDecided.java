package kh.cmt.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox event {@value #TYPE}: an admin's decision on one of the person's attendance requests took effect (step 10.1),
 * for the person's notification. {@code request} LATE_REASON | EARLY_REASON | CORRECTION; {@code decision} APPROVED |
 * REJECTED; {@code minutes} late / early minutes (reasons); {@code field} CHECK_IN | CHECK_OUT and {@code time} HH:mm
 * (corrections); {@code comment} the admin's note.
 */
public record AttendanceRequestDecided(UUID personId, String request, String decision, LocalDate date, String shiftName, Integer minutes,
        String field, String time, String comment) {

    public static final String TYPE = "attendance.request-decided";
}
