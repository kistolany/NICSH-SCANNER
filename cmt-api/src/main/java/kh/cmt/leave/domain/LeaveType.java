package kh.cmt.leave.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.i18n.LocalizedText;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A kind of leave of the school (Leave &amp; holidays › Leave types): its days per calendar year, whether it may be
 * taken by half days, when a request needs an attachment, its colour. {@code code} never changes: the apps label by it.
 */
@Entity
@Table(name = "leave_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveType extends SoftDeletable {

    /** When a request of this type needs a file (a doctor's note…). */
    public enum Attachment {
        NONE, OVER_ONE_DAY, ALWAYS
    }

    @Column(nullable = false, updatable = false)
    private String code;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "km", column = @Column(name = "name_km")),
            @AttributeOverride(name = "en", column = @Column(name = "name_en")),
            @AttributeOverride(name = "ar", column = @Column(name = "name_ar"))
    })
    private LocalizedText name;

    @Column(name = "days_per_year", nullable = false)
    private BigDecimal daysPerYear;

    @Column(name = "half_days", nullable = false)
    private boolean halfDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Attachment attachment;

    @Column(nullable = false)
    private String color;

    /** The order of the lists and of the balance columns (new types last). */
    @Column(nullable = false, updatable = false)
    private int position;

    public LeaveType(String code, int position, LocalizedText name, BigDecimal daysPerYear, boolean halfDays, Attachment attachment,
            String color) {
        this.code = code;
        this.position = position;
        set(name, daysPerYear, halfDays, attachment, color);
    }

    public void set(LocalizedText name, BigDecimal daysPerYear, boolean halfDays, Attachment attachment, String color) {
        this.name = name;
        this.daysPerYear = daysPerYear;
        this.halfDays = halfDays;
        this.attachment = attachment;
        this.color = color;
    }
}
