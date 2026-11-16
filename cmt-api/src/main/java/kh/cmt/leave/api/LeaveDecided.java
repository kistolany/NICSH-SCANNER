package kh.cmt.leave.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * Outbox event {@value #TYPE}: an admin approved or rejected a leave request (step 9.2) — the person is told.
 * {@code status} APPROVED | REJECTED; {@code half} AM | PM or null; {@code comment} the admin's note.
 */
public record LeaveDecided(UUID requestId, UUID personId, String status, LocalizedName typeName, LocalDate startDate, LocalDate endDate,
        String half, BigDecimal days, String comment) {

    public static final String TYPE = "leave.decided";
}
