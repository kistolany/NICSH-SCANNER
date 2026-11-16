package kh.cmt.approvals.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * What a module does when one of its requests is decided (the architecture's handler per type). An admin's decision
 * shows at once; {@link #check} runs then (throwing refuses it, nothing is saved), {@link #decided} runs 10 s later when
 * it takes effect (Undo until then, so nothing ever has to be reversed), in its own transaction, without a signed-in
 * caller, for the request's school.
 */
public interface ApprovalHandler {

    Approvals.Type type();

    /** {@code by}: the deciding account; {@code comment} the admin's note (always set on a rejection). */
    record Decided(UUID approvalId, UUID subjectId, UUID personId, Approvals.Status decision, UUID by, Instant at, String comment,
            Map<String, Object> details) {
    }

    /** When the admin decides: can it be applied? Throw an {@code ApiException} if not. */
    default void check(Decided decided) {
    }

    /** The decision takes effect: change the module's own things, tell the person. */
    void decided(Decided decided);

    /** How many days the request is for, for the approver's limit (Roles › Approval limits); 0 when not a leave. */
    default double leaveDays(Map<String, Object> details) {
        return 0;
    }

    /** The day a correction is for, for the approver's limit (old corrections); null when not a correction. */
    default LocalDate correctionDay(Map<String, Object> details) {
        return null;
    }
}
