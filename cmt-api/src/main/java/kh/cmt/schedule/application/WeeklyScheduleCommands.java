package kh.cmt.schedule.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the Admin Web sends for weekly schedules. */
public final class WeeklyScheduleCommands {

    private WeeklyScheduleCommands() {
    }

    /**
     * The week from {@code effectiveFrom} on (today or later): the seven {@code days} (Monday = 1 … Sunday = 7), each a
     * template id or null = off. Or, for a department or a person, {@code inherit: true} and no days: from that date
     * follow the level above again. Saving on the date of an existing version replaces it.
     */
    public record Save(LocalDate effectiveFrom, List<Day> days, Boolean inherit) {
    }

    public record Day(Integer weekday, UUID templateId) {
    }
}
