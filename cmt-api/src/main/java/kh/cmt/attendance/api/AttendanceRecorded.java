package kh.cmt.attendance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox event {@value #TYPE}: a check-in or check-out was recorded (step 7.2), written in the transaction of the scan.
 * {@code kind} CHECK_IN | CHECK_OUT; {@code at} the time that counts; {@code status} the record's status after it
 * (ON_TIME | LATE | EARLY); {@code source} SCAN | OFFLINE | MANUAL. For the end-of-day summary (7.8), the admin dashboard
 * and notifications later.
 */
public record AttendanceRecorded(UUID recordId, UUID personId, LocalDate workDate, int shiftIdx, String kind, Instant at, String status,
        int lateMinutes, int earlyMinutes, String source) {

    public static final String TYPE = "attendance.recorded";
}
