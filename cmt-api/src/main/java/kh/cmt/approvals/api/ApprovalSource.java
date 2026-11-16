package kh.cmt.approvals.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests a module keeps in its own store that still belong in the one inbox (device changes: the auth server writes
 * them). The inbox lists them with the others and decides them here, at once (no 10 s wait, no Undo). Only the people
 * the caller may see.
 */
public interface ApprovalSource {

    Approvals.Type type();

    /** {@code status} PENDING, APPROVED or REJECTED. */
    record Request(UUID id, UUID personId, Approvals.Status status, String reason, Map<String, Object> details, Instant requestedAt,
            Instant decidedAt, UUID decidedBy, String comment) {
    }

    /** {@code open}: the waiting ones, oldest first; else the decided ones, newest first (at most {@code limit}). */
    List<Request> list(boolean open, int limit);

    Optional<Request> find(UUID id);

    void approve(UUID id, String comment);

    void reject(UUID id, String comment);
}
