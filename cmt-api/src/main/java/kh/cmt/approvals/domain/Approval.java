package kh.cmt.approvals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A request waiting for an admin, or how it was decided. */
@Entity
@Table(name = "approval")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Approval extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Approvals.Type type;

    @Column(name = "subject_type", nullable = false, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Approvals.Status status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    private String comment;

    @Column(name = "escalated_to_role")
    private String escalatedToRole;

    /** When the decision takes effect (the handler runs); until then it can be undone. */
    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "effect_error")
    private String effectError;

    public static Approval pending(Approvals.Request r, UUID requestedBy, Instant at) {
        Approval a = new Approval();
        a.type = r.type();
        a.subjectType = r.subjectType();
        a.subjectId = r.subjectId();
        a.personId = r.personId();
        a.requestedBy = requestedBy;
        a.requestedAt = at;
        a.status = Approvals.Status.PENDING;
        a.change(r.reason(), r.details());
        return a;
    }

    public boolean open() {
        return status == Approvals.Status.PENDING;
    }

    /**
     * Approved or rejected by an admin ({@code comment} required to reject: checked by the caller); it takes effect at
     * {@code effectiveAt}.
     */
    public void decide(Approvals.Status decision, UUID by, Instant at, String comment, Instant effectiveAt) {
        if (!open()) {
            throw new IllegalStateException("already decided");
        }
        if (decision != Approvals.Status.APPROVED && decision != Approvals.Status.REJECTED) {
            throw new IllegalArgumentException("approve or reject");
        }
        this.status = decision;
        this.decidedBy = by;
        this.decidedAt = at;
        this.comment = comment;
        this.effectiveAt = effectiveAt;
    }

    /** Decided and still waiting to take effect (Undo is possible until {@link #getEffectiveAt}). */
    public boolean waiting() {
        return effectiveAt != null && appliedAt == null;
    }

    /** Undo: waiting again, as if never decided. */
    public void undo() {
        if (!waiting()) {
            throw new IllegalStateException("nothing to undo");
        }
        this.status = Approvals.Status.PENDING;
        this.decidedBy = null;
        this.decidedAt = null;
        this.comment = null;
        this.effectiveAt = null;
    }

    /** The effect ran ({@code error}: why it could not; the decision stays). */
    public void applied(Instant at, String error) {
        this.appliedAt = at;
        this.effectError = error == null ? null : error.length() > 200 ? error.substring(0, 200) : error;
    }

    /** What the other modules see: a decision only once it took effect. */
    public Approvals.Status shownStatus() {
        return waiting() ? Approvals.Status.PENDING : status;
    }

    /** Taken back by the person while it was waiting. */
    public void cancel(UUID by, Instant at) {
        if (!open()) {
            throw new IllegalStateException("already decided");
        }
        this.status = Approvals.Status.CANCELLED;
        this.decidedBy = by;
        this.decidedAt = at;
    }

    /** Sent again while open: the new reason and details replace the old ones. */
    public void change(String reason, Map<String, Object> details) {
        if (!open()) {
            throw new IllegalStateException("a decided request cannot change");
        }
        this.reason = reason;
        this.details = details == null || details.isEmpty() ? null : new LinkedHashMap<>(details);
    }
}
