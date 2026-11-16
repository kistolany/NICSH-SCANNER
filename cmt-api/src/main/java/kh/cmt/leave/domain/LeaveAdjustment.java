package kh.cmt.leave.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;

/** Days an admin added (+) or took away (−) from a person's balance of a type in a year, and why. Never changed. */
@Entity
@Table(name = "leave_adjustment")
@Immutable
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveAdjustment extends BaseEntity {

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private BigDecimal days;

    @Column(nullable = false)
    private String reason;

    public LeaveAdjustment(UUID personId, UUID leaveTypeId, int year, BigDecimal days, String reason) {
        this.personId = personId;
        this.leaveTypeId = leaveTypeId;
        this.year = year;
        this.days = days;
        this.reason = reason;
    }
}
