package kh.cmt.schedule.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;

/**
 * One version of a weekly schedule: for everyone, a department or a person, from {@code effectiveFrom} on. Seven days,
 * each a shift template or off; or {@code inherit}: follow the level above again. A person's version is only seen
 * within the record scope; versions for everyone and for departments are seen by all.
 */
@Entity
@Table(name = "weekly_schedule")
@Filter(name = ScopeFilter.NAME, condition = "({alias}.person_id is null or " + ScopeFilter.ON_PERSON_ID + ")",
        deduceAliasInjectionPoints = false)
public class WeeklySchedule extends BaseEntity {

    public enum Target { EVERYONE, DEPARTMENT, PERSON }

    /** A day: ISO weekday (Monday = 1) and the template, null = off. */
    @Embeddable
    public static class Day {

        private int weekday;

        @Column(name = "template_id")
        private UUID templateId;

        protected Day() {
        }

        public Day(int weekday, UUID templateId) {
            this.weekday = weekday;
            this.templateId = templateId;
        }

        public int getWeekday() {
            return weekday;
        }

        public UUID getTemplateId() {
            return templateId;
        }
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", updatable = false)
    private Target targetType;

    @Column(name = "department_id", updatable = false)
    private UUID departmentId;

    @Column(name = "person_id", updatable = false)
    private UUID personId;

    @Column(name = "effective_from", updatable = false)
    private LocalDate effectiveFrom;

    private boolean inherit;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "weekly_schedule_day", joinColumns = @JoinColumn(name = "schedule_id"))
    @OrderBy("weekday")
    private List<Day> days = new ArrayList<>();

    protected WeeklySchedule() {
    }

    public WeeklySchedule(Target targetType, UUID departmentId, UUID personId, LocalDate effectiveFrom) {
        this.targetType = targetType;
        this.departmentId = departmentId;
        this.personId = personId;
        this.effectiveFrom = effectiveFrom;
    }

    /** Seven days of its own, or none with {@code inherit}. */
    public void set(boolean inherit, List<Day> days) {
        this.inherit = inherit;
        this.days.clear();
        if (!inherit) {
            this.days.addAll(days);
        }
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

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public boolean isInherit() {
        return inherit;
    }

    public List<Day> getDays() {
        return days;
    }

    /** The template of an ISO weekday, null = off. */
    public UUID templateOn(int weekday) {
        return days.stream().filter(d -> d.getWeekday() == weekday).findFirst().map(Day::getTemplateId).orElse(null);
    }
}
