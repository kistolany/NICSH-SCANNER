package kh.cmt.schedule.api;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * Holidays and date changes that apply to a person on a day, for the "today's schedule" resolver (step 5.4), leave and
 * reports. Whole school, whatever the caller's record scope. A holiday wins over a date change, and a date change over
 * the weekly schedule ({@link WeeklySchedules}).
 */
public interface SpecialDays {

    /** {@code kind} NATIONAL | SCHOOL. */
    record HolidayDay(UUID id, LocalizedName name, String kind) {
    }

    /** {@code source} EVERYONE | DEPARTMENT | PERSON; {@code templateId} null = off that day. */
    record ChangeDay(UUID id, String source, UUID templateId, String label) {
    }

    /** The holiday of that day for someone of this department (null department: holidays for everyone only). */
    Optional<HolidayDay> holidayOn(UUID departmentOfPerson, LocalDate day);

    /** The most specific date change of that day for the person (person &gt; department &gt; everyone). */
    Optional<ChangeDay> dateChangeOn(UUID personId, UUID departmentOfPerson, LocalDate day);

    /** The days from {@code from} to {@code to} with a holiday or a date change that reaches the person, in order. */
    java.util.SortedSet<LocalDate> specialDates(UUID personId, UUID departmentOfPerson, LocalDate from, LocalDate to);

    /** {@link #holidayOn} for every day of {@code from} – {@code to} at once (days without one left out). */
    Map<LocalDate, HolidayDay> holidaysIn(UUID departmentOfPerson, LocalDate from, LocalDate to);

    /** {@link #dateChangeOn} for every day of {@code from} – {@code to} at once (days without one left out). */
    Map<LocalDate, ChangeDay> dateChangesIn(UUID personId, UUID departmentOfPerson, LocalDate from, LocalDate to);
}
