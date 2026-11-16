package kh.cmt.iam.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What the approval limits look at in a request.
 *
 * @param kind        what is asked for
 * @param leaveDays   length of a leave request, in days (0 for other kinds)
 * @param day         for a correction: the day being corrected (null for other kinds)
 * @param requesterId the person who made the request
 */
public record ApprovalRequest(Kind kind, int leaveDays, LocalDate day, UUID requesterId) {

    public enum Kind { LEAVE, CORRECTION, OTHER }

    public static ApprovalRequest leave(int days, UUID requesterId) {
        return new ApprovalRequest(Kind.LEAVE, days, null, requesterId);
    }

    public static ApprovalRequest correction(LocalDate day, UUID requesterId) {
        return new ApprovalRequest(Kind.CORRECTION, 0, day, requesterId);
    }

    public static ApprovalRequest other(UUID requesterId) {
        return new ApprovalRequest(Kind.OTHER, 0, null, requesterId);
    }
}
