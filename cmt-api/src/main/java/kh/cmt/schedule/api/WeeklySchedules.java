package kh.cmt.schedule.api;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The weekly schedule that applies to a person on a day (person &gt; department &gt; everyone), for the "today's
 * schedule" resolver (step 5.4) and reports. Whole school, whatever the caller's record scope.
 */
public interface WeeklySchedules {

    /**
     * {@code source} EVERYONE | DEPARTMENT | PERSON; {@code templates}: ISO weekday (Monday = 1) → shift template, a
     * missing weekday = off.
     */
    record Week(String source, LocalDate effectiveFrom, Map<Integer, UUID> templates) {
    }

    /** Empty when nobody set a schedule that reaches the person. */
    Optional<Week> weekOf(UUID personId, LocalDate day);

    /** {@link #weekOf} for every day of {@code from} – {@code to} at once (days without a schedule left out). */
    Map<LocalDate, Week> weeksOf(UUID personId, LocalDate from, LocalDate to);
}
