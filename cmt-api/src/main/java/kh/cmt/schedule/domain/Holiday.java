package kh.cmt.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * A holiday from {@code startDate} to {@code endDate} (both included): a national holiday or a school break, for
 * everyone or one department ({@code departmentId}). Nobody of it works then: no absence is counted, the app shows
 * "Holiday", and date changes on those days do not apply.
 */
@Entity
@Table(name = "holiday")
public class Holiday extends BaseEntity {

    public enum Kind { NATIONAL, SCHOOL }

    @Column(name = "name_km")
    private String nameKm;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "department_id")
    private UUID departmentId;

    protected Holiday() {
    }

    public Holiday(LocalizedName name, Kind kind, LocalDate startDate, LocalDate endDate, UUID departmentId) {
        set(name, kind, startDate, endDate, departmentId);
    }

    public final void set(LocalizedName name, Kind kind, LocalDate startDate, LocalDate endDate, UUID departmentId) {
        this.nameKm = name.km();
        this.nameEn = name.en();
        this.nameAr = name.ar();
        this.kind = kind;
        this.startDate = startDate;
        this.endDate = endDate;
        this.departmentId = departmentId;
    }

    public LocalizedName getName() {
        return new LocalizedName(nameKm, nameEn, nameAr);
    }

    public Kind getKind() {
        return kind;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(startDate) && !day.isAfter(endDate);
    }

    /** Does it reach someone of this department (null = no department: only holidays for everyone)? */
    public boolean reaches(UUID departmentOfPerson) {
        return departmentId == null || departmentId.equals(departmentOfPerson);
    }
}
