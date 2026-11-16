package kh.cmt.leave.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outbox event {@value #TYPE}: leave was approved for these working dates (step 9.2) — attendance turns the absences the
 * end-of-day job already stored for them into leave.
 */
public record LeaveApproved(UUID requestId, UUID personId, List<LocalDate> dates) {

    public static final String TYPE = "leave.approved";
}
