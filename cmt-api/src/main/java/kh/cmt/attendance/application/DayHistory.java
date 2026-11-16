package kh.cmt.attendance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.schedule.api.DaySchedules;

/**
 * One day of a person's attendance history (step 7.5), from the day's schedule and its records. No database: every case
 * is unit-tested ({@code DayHistoryTest}).
 * <ul>
 * <li>Future days, days off and days without a schedule that have no record → no row.</li>
 * <li>A holiday / a day all on leave without a record → HOLIDAY / LEAVE.</li>
 * <li>A <b>past</b> working day without a scan → ABSENT (until the end-of-day job, 7.8, stores ABSENT records; they mean
 * the same). Today without a scan yet → no row.</li>
 * <li>Shifts: from the record (its status; checked in but not out on a past day → MISSING), else LEAVE (on leave),
 * ABSENT (past) or NOT_YET (today).</li>
 * <li>Day status, worst first: MISSING (a shift not checked out, or a shift missed on a day that was otherwise worked),
 * LATE, EARLY, ON_TIME.</li>
 * </ul>
 */
final class DayHistory {

    private DayHistory() {
    }

    /** NOT_YET and OUTSIDE_AREA only on the admin's view of today ({@link #todayWithoutScan}). */
    enum Status { ON_TIME, LATE, EARLY, MISSING, ABSENT, LEAVE, HOLIDAY, NOT_YET, OUTSIDE_AREA }

    enum ShiftStatus { ON_TIME, LATE, EARLY, MISSING, ABSENT, LEAVE, NOT_YET }

    /** One shift of the day: as planned (from the record when there is one) and how it went. */
    record Line(int shiftIdx, String name, LocalTime start, LocalTime end, AttendanceRecord record, ShiftStatus status) {
    }

    record Day(LocalDate date, Status status, DaySchedules.ScheduledDay schedule, List<Line> lines) {

        int workedMinutes() {
            return sum(AttendanceRecord::getWorkedMinutes);
        }

        int lateMinutes() {
            return sum(AttendanceRecord::getLateMinutes);
        }

        int earlyMinutes() {
            return sum(AttendanceRecord::getEarlyMinutes);
        }

        /** Late minutes not excused by an admin (reports, the daily summary). */
        int countedLateMinutes() {
            return sum(AttendanceRecord::countedLateMinutes);
        }

        int countedEarlyMinutes() {
            return sum(AttendanceRecord::countedEarlyMinutes);
        }

        private int sum(ToIntFunction<AttendanceRecord> f) {
            return lines.stream().filter(l -> l.record() != null).mapToInt(l -> f.applyAsInt(l.record())).sum();
        }
    }

    static Optional<Day> of(LocalDate today, DaySchedules.ScheduledDay day, List<AttendanceRecord> records) {
        LocalDate date = day.date();
        if (date.isAfter(today)) {
            return Optional.empty();
        }
        boolean past = date.isBefore(today);
        List<Line> lines = lines(day, records, past);
        boolean attended = records.stream().anyMatch(r -> r.getInAt() != null);
        if (!attended) {
            return switch (day.kind()) {
                case HOLIDAY -> Optional.of(new Day(date, Status.HOLIDAY, day, List.of()));
                case LEAVE -> Optional.of(new Day(date, Status.LEAVE, day, lines));
                case WORK -> past || lines.stream().anyMatch(l -> l.status() == ShiftStatus.ABSENT)
                        ? Optional.of(new Day(date, absentOrLeave(lines), day, lines))
                        : Optional.empty();   // today, nothing scanned yet
                case OFF, NO_SCHEDULE -> records.isEmpty() ? Optional.<Day>empty() : Optional.of(new Day(date, absentOrLeave(lines), day, lines));
            };
        }
        return Optional.of(new Day(date, worst(lines), day, lines));
    }

    /**
     * The admin's view of today for someone who has not scanned (step 7.7; the staff app shows no row then): their
     * shifts NOT_YET while one is still to end, ABSENT after the last one ended; OUTSIDE_AREA when a scan was refused
     * for being outside the school area. Empty when it is not a working day.
     */
    static Optional<Day> todayWithoutScan(DaySchedules.ScheduledDay day, Instant now, ZoneId zone, boolean triedOutside) {
        List<DaySchedules.Shift> working = day.shifts().stream().filter(s -> !s.onLeave()).toList();
        if (day.kind() != DaySchedules.Kind.WORK || working.isEmpty()) {
            return Optional.empty();
        }
        Instant lastEnd = working.stream().map(s -> day.date().atTime(s.end()).atZone(zone).toInstant()).max(Comparator.naturalOrder()).orElseThrow();
        boolean over = !now.isBefore(lastEnd);
        List<Line> lines = day.shifts().stream()
                .map(s -> new Line(s.position(), s.name(), s.start(), s.end(), null, s.onLeave() ? ShiftStatus.LEAVE
                        : now.isBefore(day.date().atTime(s.end()).atZone(zone).toInstant()) ? ShiftStatus.NOT_YET : ShiftStatus.ABSENT))
                .toList();
        Status status = triedOutside ? Status.OUTSIDE_AREA : over ? Status.ABSENT : Status.NOT_YET;
        return Optional.of(new Day(day.date(), status, day, lines));
    }

    /**
     * Today as the staff app's Today card needs it (step 7.9): every planned shift, also before the first scan (a
     * shift without a record is NOT_YET or LEAVE); the day's status as {@link #of} once something was scanned.
     */
    static Day today(DaySchedules.ScheduledDay day, List<AttendanceRecord> records) {
        List<Line> lines = lines(day, records, false);
        boolean attended = records.stream().anyMatch(r -> r.getInAt() != null);
        return new Day(day.date(), attended ? worst(lines) : Status.NOT_YET, day, lines);
    }

    /** The planned shifts with their records, and records of shifts no longer planned (the schedule changed since). */
    private static List<Line> lines(DaySchedules.ScheduledDay day, List<AttendanceRecord> records, boolean past) {
        Map<Integer, AttendanceRecord> byShift = records.stream().collect(Collectors.toMap(AttendanceRecord::getShiftIdx, Function.identity()));
        List<Line> lines = new ArrayList<>();
        boolean planned = day.kind() == DaySchedules.Kind.WORK || day.kind() == DaySchedules.Kind.LEAVE;
        if (planned) {
            for (DaySchedules.Shift s : day.shifts()) {
                AttendanceRecord r = byShift.remove(s.position());
                lines.add(r != null ? line(r, past)
                        : new Line(s.position(), s.name(), s.start(), s.end(), null,
                                s.onLeave() ? ShiftStatus.LEAVE : past ? ShiftStatus.ABSENT : ShiftStatus.NOT_YET));
            }
        }
        byShift.values().forEach(r -> lines.add(line(r, past)));
        lines.sort(Comparator.comparingInt(Line::shiftIdx));
        return lines;
    }

    private static Line line(AttendanceRecord r, boolean past) {
        ShiftStatus status = switch (r.getStatus()) {
            case ABSENT -> ShiftStatus.ABSENT;
            case LEAVE -> ShiftStatus.LEAVE;
            case MISSING -> ShiftStatus.MISSING;
            case LATE, EARLY, ON_TIME -> r.getInAt() != null && r.getOutAt() == null && past ? ShiftStatus.MISSING
                    : ShiftStatus.valueOf(r.getStatus().name());
        };
        return new Line(r.getShiftIdx(), r.getShiftName(), r.getPlannedStart(), r.getPlannedEnd(), r, status);
    }

    private static Status absentOrLeave(List<Line> lines) {
        return !lines.isEmpty() && lines.stream().allMatch(l -> l.status() == ShiftStatus.LEAVE) ? Status.LEAVE : Status.ABSENT;
    }

    private static Status worst(List<Line> lines) {
        if (lines.stream().anyMatch(l -> l.status() == ShiftStatus.MISSING || l.status() == ShiftStatus.ABSENT)) {
            return Status.MISSING;
        }
        if (lines.stream().anyMatch(l -> l.status() == ShiftStatus.LATE)) {
            return Status.LATE;
        }
        if (lines.stream().anyMatch(l -> l.status() == ShiftStatus.EARLY)) {
            return Status.EARLY;
        }
        return Status.ON_TIME;
    }
}
