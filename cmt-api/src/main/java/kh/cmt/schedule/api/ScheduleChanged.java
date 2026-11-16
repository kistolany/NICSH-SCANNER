package kh.cmt.schedule.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * Outbox event {@value #TYPE}: a schedule change that reaches these people (step 5.5). Written in the transaction of the
 * change, only for people whose day or week really changes (today or later), so the notifications module can tell
 * them. Fields not used by a kind are null / empty.
 *
 * <ul>
 * <li>{@code DAYS} — date changes saved on {@code dates}: {@code label} ("Exam day"), {@code templateName},
 * {@code times} ("07:00 – 12:00"), or {@code off}; {@code removed}: a date change was taken away (the day is
 * whatever applies now).</li>
 * <li>{@code WEEK} — the weekly schedule that applies to them changes {@code from} a date.</li>
 * <li>{@code SHIFT_TIMES} — a template they work was changed: {@code templateName}, its new {@code times}.</li>
 * <li>{@code HOLIDAY_ADDED} / {@code HOLIDAY_CHANGED} / {@code HOLIDAY_REMOVED} — {@code holidayName},
 * {@code holidayStart} … {@code holidayEnd} (for a change: the new days).</li>
 * </ul>
 */
public record ScheduleChanged(Kind kind, List<UUID> personIds, List<LocalDate> dates, LocalDate from, String label,
        String templateName, List<String> times, boolean off, boolean removed, LocalizedName holidayName, LocalDate holidayStart,
        LocalDate holidayEnd) {

    public static final String TYPE = "schedule.changed";

    public enum Kind { DAYS, WEEK, SHIFT_TIMES, HOLIDAY_ADDED, HOLIDAY_CHANGED, HOLIDAY_REMOVED }

    public static ScheduleChanged days(List<UUID> people, List<LocalDate> dates, String label, String templateName, List<String> times,
            boolean off) {
        return new ScheduleChanged(Kind.DAYS, people, dates, null, label, templateName, times, off, false, null, null, null);
    }

    public static ScheduleChanged daysRemoved(List<UUID> people, List<LocalDate> dates) {
        return new ScheduleChanged(Kind.DAYS, people, dates, null, null, null, List.of(), false, true, null, null, null);
    }

    public static ScheduleChanged week(List<UUID> people, LocalDate from) {
        return new ScheduleChanged(Kind.WEEK, people, List.of(), from, null, null, List.of(), false, false, null, null, null);
    }

    public static ScheduleChanged shiftTimes(List<UUID> people, String templateName, List<String> times) {
        return new ScheduleChanged(Kind.SHIFT_TIMES, people, List.of(), null, null, templateName, times, false, false, null, null, null);
    }

    public static ScheduleChanged holiday(Kind kind, List<UUID> people, LocalizedName name, LocalDate start, LocalDate end) {
        return new ScheduleChanged(kind, people, List.of(), null, null, null, List.of(), false, false, name, start, end);
    }
}
