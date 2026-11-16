package kh.cmt.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.schedule.domain.WeeklySchedule.Target;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;

/**
 * A date change: on one date, everyone / a department / a person works another shift template, or is off
 * ({@code templateId} null), with an optional label such as "Exam day". It wins over the weekly schedule, and the
 * most specific one of a date wins (person &gt; department &gt; everyone); on a holiday it does not apply. A person's
 * date change is only seen within the record scope.
 */
@Entity
@Table(name = "schedule_date_change")
@Filter(name = ScopeFilter.NAME, condition = "({alias}.person_id is null or " + ScopeFilter.ON_PERSON_ID + ")",
        deduceAliasInjectionPoints = false)
public class DateChange extends BaseEntity {

    @Column(name = "change_date", updatable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", updatable = false)
    private Target targetType;

    @Column(name = "department_id", updatable = false)
    private UUID departmentId;

    @Column(name = "person_id", updatable = false)
    private UUID personId;

    @Column(name = "template_id")
    private UUID templateId;

    private String label;

    protected DateChange() {
    }

    public DateChange(LocalDate date, Target targetType, UUID departmentId, UUID personId) {
        this.date = date;
        this.targetType = targetType;
        this.departmentId = departmentId;
        this.personId = personId;
    }

    public void set(UUID templateId, String label) {
        this.templateId = templateId;
        this.label = label;
    }

    public LocalDate getDate() {
        return date;
    }

    public Target getTargetType() {
        return targetType;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public UUID getPersonId() {
        return personId;
    }

    /** The department or person, null for everyone. */
    public UUID targetId() {
        return departmentId != null ? departmentId : personId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public boolean isOff() {
        return templateId == null;
    }

    public String getLabel() {
        return label;
    }

    /** Does it reach this person (their own, their department's, or everyone's)? */
    public boolean reaches(UUID personId, UUID departmentOfPerson) {
        return switch (targetType) {
            case EVERYONE -> true;
            case DEPARTMENT -> departmentId.equals(departmentOfPerson);
            case PERSON -> this.personId.equals(personId);
        };
    }
}
