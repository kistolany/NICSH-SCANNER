package kh.cmt.iam.domain;

/**
 * What a role may approve (Roles page, "Approval limits"). Above the limit, the request goes to the role
 * {@code escalateTo} instead.
 *
 * @param leaveMaxDays   longest leave the role may approve, in days; null = no limit
 * @param oldCorrections may approve attendance corrections for a day more than 7 days ago
 * @param ownRequests    may approve requests the user made themselves (off by default)
 * @param escalateTo     role code that gets what is above the limit; null only for a role without limits
 */
public record ApprovalLimits(Integer leaveMaxDays, boolean oldCorrections, boolean ownRequests, String escalateTo) {

    /** How old a correction may be (in days) before it needs {@code oldCorrections}. */
    public static final int CORRECTION_DAYS = 7;

    public static final ApprovalLimits UNLIMITED = new ApprovalLimits(null, true, false, null);

    public static ApprovalLimits standard() {
        return new ApprovalLimits(3, false, false, SystemRole.SUPER_ADMIN.code());
    }
}
