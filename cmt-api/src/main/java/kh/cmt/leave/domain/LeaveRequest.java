package kh.cmt.leave.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A person's request for leave: whole days or half a day, and where it stands. */
@Entity
@Table(name = "leave_request")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequest extends BaseEntity {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    /** Half a day: the morning or the afternoon. */
    public enum Half { AM, PM }

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "leave_type_id", nullable = false, updatable = false)
    private UUID leaveTypeId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Half half;

    @Column(nullable = false, updatable = false)
    private BigDecimal days;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_dates", nullable = false, updatable = false)
    private List<String> workingDates;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "attachment_file_id", updatable = false)
    private UUID attachmentFileId;

    @Column(name = "short_notice", nullable = false, updatable = false)
    private boolean shortNotice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    private String comment;

    public LeaveRequest(UUID personId, UUID leaveTypeId, LocalDate startDate, LocalDate endDate, Half half, BigDecimal days,
            List<LocalDate> workingDates, String reason, UUID attachmentFileId, boolean shortNotice) {
        this.personId = personId;
        this.leaveTypeId = leaveTypeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.half = half;
        this.days = days;
        this.workingDates = new ArrayList<>(workingDates.stream().map(LocalDate::toString).toList());
        this.reason = reason;
        this.attachmentFileId = attachmentFileId;
        this.shortNotice = shortNotice;
        this.status = Status.PENDING;
    }

    public boolean open() {
        return status == Status.PENDING;
    }

    /** Approved, rejected (with the admin's note) or taken back by the person. */
    public void close(Status to, UUID by, Instant at, String comment) {
        if (!open() || to == Status.PENDING) {
            throw new IllegalStateException("only a waiting request can be decided or cancelled");
        }
        this.status = to;
        this.decidedBy = by;
        this.decidedAt = at;
        this.comment = comment;
    }

    /** Do the two cover some of the same time (half days of one date only when the same half, or one is whole)? */
    public boolean overlaps(LocalDate start, LocalDate end, Half otherHalf) {
        if (end.isBefore(startDate) || start.isAfter(endDate)) {
            return false;
        }
        return half == null || otherHalf == null || half == otherHalf;
    }
}
