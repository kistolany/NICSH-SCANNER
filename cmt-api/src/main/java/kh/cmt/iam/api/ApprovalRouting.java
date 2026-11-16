package kh.cmt.iam.api;

import java.util.UUID;

/**
 * Approval limits per role (Roles page, "Approval limits"), for the approvals module: who may approve a request,
 * and where it goes when it is above a role's limits.
 * <p>
 * A role covers a request when the leave is not longer than its {@code leaveMaxDays}, a correction is not for a day
 * more than 7 days ago (unless it may approve old corrections), and the request was not made by the approver
 * themselves (unless it may approve its own requests). Otherwise the request moves to the role's escalation role, and
 * on up the chain. Super Admin has no limits and is where every chain ends (also when an escalation role was deleted
 * or the chain runs in a circle).
 */
public interface ApprovalRouting {

    /** The role whose inbox a new request lands in, starting from the role that would normally approve it. */
    String inboxFor(UUID schoolId, String startRole, ApprovalRequest request);

    /**
     * Whether this approver (role and linked person) may decide the request; if not, the role it moves to.
     *
     * @param approverPersonId the approver's linked person (null if none), to spot their own requests
     */
    Decision check(UUID schoolId, String approverRole, UUID approverPersonId, ApprovalRequest request);

    /**
     * The role whose limits an admin of the mobile admin app decides with (their app role {@code admin} is not a web
     * role): the role of the web user linked to their person, else the school's default role for new web users.
     */
    String appAdminRole(UUID schoolId, UUID personId);

    /** {@code allowed}, or the role the request must move to ({@code escalateTo}) and why. */
    record Decision(boolean allowed, String escalateTo, Reason reason) {

        public enum Reason { LEAVE_TOO_LONG, CORRECTION_TOO_OLD, OWN_REQUEST }

        public static Decision allow() {
            return new Decision(true, null, null);
        }
    }
}
