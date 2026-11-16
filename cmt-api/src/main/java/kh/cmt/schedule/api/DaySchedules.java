package kh.cmt.schedule.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * "Today's schedule": what a person works on a day, for the scan, the Today card, My schedule and reports. Whole
 * school, whatever the caller's record scope.
 * <p>
 * Order: a <b>holiday</b> (nobody of it works) → a <b>date change</b> (most specific: person &gt; department &gt;
 * everyone) → the <b>weekly schedule</b> (same order); then <b>leave</b> marks the shifts the person is away for.
 */
public interface DaySchedules {

    ScheduledDay forPerson(UUID personId, LocalDate date);

    /**
     * {@link #forPerson(UUID, LocalDate)} for every day of {@code from} – {@code to}, oldest first — the same answers,
     * with the person's holidays, date changes and weekly schedules read once (history, admin attendance, reports).
     */
    List<ScheduledDay> forPerson(UUID personId, LocalDate from, LocalDate to);

    /**
     * WORK: at least one shift to work; OFF: a day off (weekly schedule or date change); HOLIDAY; LEAVE: every shift of
     * the day is on leave; NO_SCHEDULE: nobody set a schedule that reaches the person.
     */
    enum Kind { WORK, OFF, HOLIDAY, LEAVE, NO_SCHEDULE }

    /**
     * One day. {@code source} HOLIDAY | DATE_CHANGE | WEEKLY (null for NO_SCHEDULE), {@code level} where the date change
     * or week comes from (EVERYONE | DEPARTMENT | PERSON); {@code changed}: a date change applies (the app's "Changed");
     * {@code label} the date change's label ("Exam day"). {@code shifts}: 0, 1 or 2, by position (on a leave day they
     * are kept, each marked {@code onLeave}, so reports can count the hours).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ScheduledDay(LocalDate date, Kind kind, String source, String level, boolean changed, String label, Template template,
            Holiday holiday, Leave leave, List<Shift> shifts) {
    }

    record Template(UUID id, String name, String color) {
    }

    /**
     * A shift with its times and the template's rules: minutes late allowed, whether leaving early needs a reason, and
     * {@code checkInOpensMinutes} before {@code start} from which a scan checks in (null = no limit).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Shift(int position, String name, @JsonFormat(pattern = "HH:mm") LocalTime start,
            @JsonFormat(pattern = "HH:mm") LocalTime end, long minutes, int graceMinutes,
            boolean earlyCheckOutNeedsReason, boolean onLeave, Integer checkInOpensMinutes) {

        public Shift(int position, String name, LocalTime start, LocalTime end, long minutes, int graceMinutes,
                boolean earlyCheckOutNeedsReason, boolean onLeave) {
            this(position, name, start, end, minutes, graceMinutes, earlyCheckOutNeedsReason, onLeave, null);
        }

        /** The earliest moment a scan checks in for this shift (start − opens), or null with no limit. */
        public LocalTime checkInOpensAt() {
            if (checkInOpensMinutes == null) {
                return null;
            }
            LocalTime opens = start.minusMinutes(checkInOpensMinutes);
            return opens.isAfter(start) ? LocalTime.MIN : opens;   // would wrap before midnight: from the day's start
        }
    }

    /** {@code kind} NATIONAL | SCHOOL. */
    record Holiday(UUID id, LocalizedName name, String kind) {
    }

    /**
     * {@code wholeDay}: the whole day is on leave, else only the shifts marked {@code onLeave}; {@code half} AM | PM for a
     * half day (on a one-shift day the shift's times are then its other half).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Leave(UUID id, String type, boolean wholeDay, String half) {

        public Leave(UUID id, String type, boolean wholeDay) {
            this(id, type, wholeDay, null);
        }
    }
}
