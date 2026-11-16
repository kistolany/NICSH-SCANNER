package kh.cmt.approvals.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests that wait for an admin (one inbox for all types, phase 10). A module sends a request about one of its own
 * things (the {@code subject}: an attendance record, a leave request…); there is at most one open request per subject and
 * type, so sending again while it is open changes its reason and details. Admins approve / reject them in the inbox; the
 * module's {@link ApprovalHandler} applies a decision 10 s later (Undo until then), and only then do the views here show
 * it. Requests kept by another store (device changes) join the inbox through an {@link ApprovalSource}.
 */
public interface Approvals {

    enum Type { LEAVE, CORRECTION, LATE_REASON, EARLY_REASON, DEVICE_CHANGE }

    enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    /** A request from the calling person; {@code details} = what the inbox shows besides the reason. */
    record Request(Type type, String subjectType, UUID subjectId, UUID personId, String reason, Map<String, Object> details) {
    }

    /** Where a request stands; {@code details} as sent; {@code comment} = the admin's note with the decision. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record View(UUID id, Type type, Status status, String reason, Map<String, Object> details, Instant requestedAt, Instant decidedAt,
            String comment, UUID decidedBy) {
    }

    /** {@code created}: this call made the request (false = the open one was changed). */
    record Sent(View approval, boolean created) {
    }

    /**
     * Sends a request, or changes the open one of the same type about the same subject. The latest one about it already
     * decided → {@code ALREADY_DECIDED} (409).
     */
    Sent request(Request request);

    /**
     * The person takes back the open request about this subject (none → nothing happens). Already decided, also while
     * the decision can still be undone → {@code ALREADY_DECIDED}.
     */
    void cancel(Type type, UUID subjectId);

    /** The latest request of this type about this subject, if any. */
    Optional<View> latest(Type type, UUID subjectId);

    /** The same for several subjects at once: subject id → its latest request (subjects without one left out). */
    Map<UUID, View> latest(Type type, Collection<UUID> subjectIds);
}
