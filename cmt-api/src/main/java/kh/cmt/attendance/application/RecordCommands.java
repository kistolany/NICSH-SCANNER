package kh.cmt.attendance.application;

import java.util.UUID;

/** What the app sends about one of its attendance records. */
public final class RecordCommands {

    private RecordCommands() {
    }

    /** POST /app/attendance/records/{id}/late-reason: why the check-in was late (≤ {@value LateReasonService#MAX_REASON}). */
    public record LateReason(String reason) {
    }

    /**
     * POST /app/attendance/corrections: "this time is wrong". {@code date} yyyy-MM-dd, {@code shiftIdx} 1 or 2 (default
     * 1), {@code field} CHECK_IN | CHECK_OUT, {@code time} HH:mm (school time), {@code reason} (≤
     * {@value LateReasonService#MAX_REASON}), {@code attachmentFileId} an uploaded and confirmed ATTACHMENT (optional).
     */
    public record Correction(String date, Integer shiftIdx, String field, String time, String reason, UUID attachmentFileId) {
    }

    /**
     * PUT /admin/attendance/records/{id}: the times as they should be ({@code checkIn} required, {@code checkOut}
     * empty = not checked out; HH:mm school time), why (required) and the {@code version} that was read.
     */
    public record EditRecord(String checkIn, String checkOut, String reason, Long version) {
    }

    /** POST /admin/attendance/records: a shift nobody scanned, entered by an admin ("Manual"). */
    public record NewRecord(UUID personId, String date, Integer shiftIdx, String checkIn, String checkOut, String reason) {
    }
}
