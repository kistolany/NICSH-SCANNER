package kh.cmt.schedule.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Weekly schedules as the Admin Web shows them. */
public final class WeeklyScheduleView {

    private WeeklyScheduleView() {
    }

    /** Who a schedule is for: EVERYONE, or a DEPARTMENT / PERSON with its id. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(String type, UUID id) {
    }

    /** A template as a day shows it. */
    public record TemplateRef(UUID id, String name, String color, boolean active, List<Shift> shifts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shift(int position, String name, @JsonFormat(pattern = "HH:mm") LocalTime start,
            @JsonFormat(pattern = "HH:mm") LocalTime end) {
    }

    /** A day: ISO weekday and its template; {@code off} when there is none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(int weekday, boolean off, TemplateRef template) {
    }

    /**
     * One saved version: {@code status} CURRENT | UPCOMING | PAST; {@code inherit} = follows the level above from
     * {@code effectiveFrom} (no days of its own).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Version(UUID id, LocalDate effectiveFrom, boolean inherit, String status, List<Day> days, UUID createdBy,
            Instant createdAt) {
    }

    /** What applies today and where it comes from ({@code source} EVERYONE | DEPARTMENT | PERSON); null days = none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Applies(String source, LocalDate effectiveFrom, List<Day> days) {
    }

    /**
     * The page for one target: its own versions (newest first; {@code current}, {@code upcoming}) and what really
     * applies today ({@code applies}, with fall-back to the level above). {@code affectedPeople}: after a save, how many
     * people the saved version reaches (for "Save &amp; notify").
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetSchedule(Target target, Version current, List<Version> upcoming, List<Version> history, Applies applies,
            Integer affectedPeople) {
    }

    /**
     * The grouped week (Weekly schedules › preview; the app's "My schedule"): days with the same template together —
     * "Monday – Friday" for three or more days in a row, else "Monday, Wednesday"; each shift as "Morning · 07:45 –
     * 11:30" (no name and one shift: just the times). {@code label} and {@code text} are English; {@code days} lets an
     * app write its own.
     */
    public record Preview(Target target, LocalDate date, String source, List<Group> groups) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(List<Integer> days, String label, boolean off, TemplateRef template, List<String> lines) {
    }
}
