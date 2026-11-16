package kh.cmt.leave.application;

import java.math.BigDecimal;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** What the leave pages send (step 9.1); checked in the services, every problem at once. */
public final class LeaveCommands {

    private LeaveCommands() {
    }

    /**
     * POST / PUT /admin/leave-types: {@code attachment} NONE | OVER_ONE_DAY | ALWAYS; {@code version} on PUT only.
     */
    public record Type(LocalizedName name, BigDecimal daysPerYear, Boolean halfDays, String attachment, String color, Long version) {
    }

    /**
     * POST /app/leave/requests (and the preview without reason / attachment): dates {@code yyyy-MM-dd}, {@code endDate}
     * = {@code startDate} when left out; {@code half} AM | PM for half a day (one date).
     */
    public record Request(java.util.UUID typeId, String startDate, String endDate, String half, String reason, java.util.UUID attachmentFileId) {
    }

    /** POST /admin/leave-balances/{personId}/adjust: {@code days} ± (not 0), {@code year} this year by default. */
    public record Adjust(UUID typeId, Integer year, BigDecimal days, String reason) {
    }
}
