package kh.cmt.schedule.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** Date changes and holidays as the Admin Web shows them. */
public final class DateChangeView {

    private DateChangeView() {
    }

    /**
     * Who it is for: EVERYONE, a DEPARTMENT or a PERSON with its {@code id}; {@code name} the department's name or the
     * person's ({@code staffId} for a person).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Who(String type, UUID id, LocalizedName name, String staffId) {
    }

    /**
     * One date change ("Upcoming date changes"): the date, who, the template ({@code off} when none) and the label;
     * {@code holiday}: the date is a holiday for them, so the change does not apply that day.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(UUID id, LocalDate date, Who who, boolean off, WeeklyScheduleView.TemplateRef template, String label,
            boolean holiday, UUID createdBy, Instant createdAt) {
    }

    /**
     * After saving date changes: what was saved, how many existed and were replaced, the dates on which a holiday wins
     * ({@code holidayDates}: "… is a holiday. The date change will not apply on holidays.") and how many active people
     * they reach ({@code affectedPeople}, for "Save &amp; notify").
     */
    public record Saved(List<Change> changes, int replaced, List<LocalDate> holidayDates, int affectedPeople) {
    }

    /**
     * A holiday: name, kind NATIONAL | SCHOOL, dates (both included; {@code days} how many), who; {@code started}:
     * it began before today, so it can no longer be changed or removed. {@code dateChanges}: date changes on its days, which it
     * overrides.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HolidayItem(UUID id, LocalizedName name, String kind, LocalDate startDate, LocalDate endDate, int days, Who who,
            boolean started, Integer dateChanges, long version, Instant createdAt, Instant updatedAt) {
    }
}
