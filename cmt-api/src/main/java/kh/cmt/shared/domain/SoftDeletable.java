package kh.cmt.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;

/**
 * Base for anything that is never really deleted: inactive rows stay for history.
 * The "activeOnly" filter is on for every session by default, so queries skip inactive rows.
 * The Inactive tab turns it off with {@link SoftDeleteFilter#includeInactive}.
 */

@MappedSuperclass
@FilterDef(name = SoftDeletable.ACTIVE_ONLY, defaultCondition = "inactive = false", autoEnabled = true)
@Filter(name = SoftDeletable.ACTIVE_ONLY)
public abstract class SoftDeletable extends BaseEntity {

    public static final String ACTIVE_ONLY = "activeOnly";

    @Column(nullable = false)
    private boolean inactive;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "delete_reason")
    private String deleteReason;

    public void inactivate(UUID by, Instant at, String reason) {
        this.inactive = true;
        this.deletedAt = at;
        this.deletedBy = by;
        this.deleteReason = reason;
    }

    public void restore() {
        this.inactive = false;
        this.deletedAt = null;
        this.deletedBy = null;
        this.deleteReason = null;
    }

    public boolean isInactive() {
        return inactive;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public String getDeleteReason() {
        return deleteReason;
    }
}
