package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import kh.cmt.attendance.application.DayHistory.Status;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff app's attendance history (step 7.5): the days of a range with the summary row, one day in detail, the
 * calendar month. Each day comes from the day's schedule and its records ({@link DayHistory}); only the caller's own.
 * Ranges: {@code week} (Monday – today), {@code month} (the 1st – today), {@code last} (last month), {@code custom}
 * ({@code from} – {@code to}, at most {@value #MAX_DAYS} days). School time.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MyHistoryService {

    static final int MAX_DAYS = 366;
    private static final Set<String> RANGES = Set.of("week", "month", "last", "custom");

    /** The status chips (SPEC §4.7); ABSENT also shows missing check-outs. */
    enum Filter {
        ALL(s -> true), ON_TIME(s -> s == Status.ON_TIME), LATE(s -> s == Status.LATE), EARLY(s -> s == Status.EARLY),
        ABSENT(s -> s == Status.ABSENT || s == Status.MISSING), LEAVE(s -> s == Status.LEAVE);

        final Predicate<Status> shows;

        Filter(Predicate<Status> shows) {
            this.shows = shows;
        }
    }

    private final AttendanceRecordRepository records;
    private final DaySchedules schedules;
    private final ShiftViews shiftViews;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HistoryViews.Page list(String range, LocalDate from, LocalDate to, String status) {
        LocalDate today = today();
        Span span = span(range == null || range.isBlank() ? "month" : range.trim().toLowerCase(Locale.ROOT), from, to, today);
        Filter filter = filter(status);
        UUID person = me();
        List<DayHistory.Day> days = history(person, span.from(), span.to(), today);
        List<DayHistory.Day> shown = days.stream().filter(d -> filter.shows.test(d.status())).toList();
        ShiftViews.Batch views = shiftViews.batch(shown.stream().map(d -> new ShiftViews.PersonDay(person, d)).toList());
        return new HistoryViews.Page(span.from(), span.to(), filter.name(), summary(days),
                shown.stream().map(d -> day(views, person, d)).toList());
    }

    @Transactional(readOnly = true)
    public HistoryViews.Day day(LocalDate date) {
        UUID person = me();
        List<DayHistory.Day> days = history(person, date, date, today());
        if (days.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);   // a day off, without a schedule, or still to come
        }
        return day(shiftViews.batch(List.of(new ShiftViews.PersonDay(person, days.getFirst()))), person, days.getFirst());
    }

    private static HistoryViews.Day day(ShiftViews.Batch views, UUID person, DayHistory.Day d) {
        List<HistoryViews.Shift> shifts = views.shifts(new ShiftViews.PersonDay(person, d));
        boolean edited = d.lines().stream().anyMatch(l -> l.record() != null && l.record().getEditedBy() != null);
        DaySchedules.ScheduledDay s = d.schedule();
        return new HistoryViews.Day(d.date(), d.status().name(), shifts, d.workedMinutes(), d.lateMinutes(), d.earlyMinutes(),
                ShiftViews.gateOf(shifts), edited, s.holiday() == null ? null : s.holiday().name(), s.label());
    }

    @Transactional(readOnly = true)
    public HistoryViews.Calendar calendar(YearMonth month) {
        YearMonth m = month == null ? YearMonth.from(today()) : month;
        List<DayHistory.Day> days = history(me(), m.atDay(1), m.atEndOfMonth(), today());
        return new HistoryViews.Calendar(m, days.reversed().stream().map(d -> new HistoryViews.Dot(d.date(), d.status().name())).toList());
    }

    // ------------------------------------------------------------------ the days

    /** The days of {@code from} – {@code to} that have a row, newest first (also the Home's month tiles). */
    List<DayHistory.Day> history(UUID person, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate last = to.isAfter(today) ? today : to;
        if (last.isBefore(from)) {
            return List.of();
        }
        Map<LocalDate, List<AttendanceRecord>> byDay = records.findByPersonIdAndWorkDateBetweenOrderByWorkDateDescShiftIdxAsc(person, from, last)
                .stream().collect(Collectors.groupingBy(AttendanceRecord::getWorkDate));
        List<DayHistory.Day> days = new ArrayList<>();
        for (DaySchedules.ScheduledDay d : schedules.forPerson(person, from, last).reversed()) {
            DayHistory.of(today, d, byDay.getOrDefault(d.date(), List.of())).ifPresent(days::add);
        }
        return days;
    }

    private static HistoryViews.Summary summary(List<DayHistory.Day> days) {
        Map<Status, Long> count = days.stream().collect(Collectors.groupingBy(DayHistory.Day::status, Collectors.counting()));
        return new HistoryViews.Summary(count(count, Status.ON_TIME), count(count, Status.LATE), count(count, Status.EARLY),
                count(count, Status.ABSENT) + count(count, Status.MISSING), count(count, Status.LEAVE),
                days.stream().mapToInt(DayHistory.Day::workedMinutes).sum());
    }

    private static int count(Map<Status, Long> count, Status s) {
        return count.getOrDefault(s, 0L).intValue();
    }

    // ------------------------------------------------------------------ the query

    private record Span(LocalDate from, LocalDate to) {
    }

    private static Span span(String range, LocalDate from, LocalDate to, LocalDate today) {
        if (!RANGES.contains(range)) {
            throw validation("range", "OneOf");
        }
        return switch (range) {
            case "week" -> new Span(today.with(DayOfWeek.MONDAY), today);
            case "month" -> new Span(today.withDayOfMonth(1), today);
            case "last" -> {
                YearMonth last = YearMonth.from(today).minusMonths(1);
                yield new Span(last.atDay(1), last.atEndOfMonth());
            }
            default -> {
                if (from == null || to == null) {
                    throw validation(from == null ? "from" : "to", "NotNull");
                }
                if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
                    throw validation("to", "Range");
                }
                yield new Span(from, to);
            }
        };
    }

    private static Filter filter(String status) {
        if (status == null || status.isBlank()) {
            return Filter.ALL;
        }
        try {
            return Filter.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw validation("status", "OneOf");
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    private static UUID me() {
        UUID person = CurrentUser.require().personId();
        if (person == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return person;
    }

    private static ApiException validation(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", field, "rule", rule)));
    }
}
