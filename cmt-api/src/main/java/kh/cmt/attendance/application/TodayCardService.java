package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.api.TodayCard;
import kh.cmt.attendance.application.DayHistory.Status;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.domain.ScanEvent;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.ScanEventRepository;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in person's Today card and this month's tiles (step 7.9), school time. The card's state is
 * {@link TodayStates}; this class reads what it needs: today's schedule and records, the last scan, the last working
 * day's records (a missing check-out) and the requests about them.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TodayCardService implements TodayCard {

    /** How far back a missing check-out is still shown ("yesterday", or Friday seen on Monday). */
    static final int MISSING_LOOKBACK_DAYS = 7;
    /** How far ahead "Back on" is looked for on a leave day. */
    static final int LEAVE_LOOKAHEAD_DAYS = 62;
    private static final String OUTSIDE_AREA = ErrorCode.OUTSIDE_AREA.name();

    private final AttendanceRecordRepository records;
    private final ScanEventRepository scans;
    private final DaySchedules schedules;
    private final ShiftViews shiftViews;
    private final TodayMapper mapper;
    private final Approvals approvals;
    private final MyHistoryService history;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Today today() {
        UUID person = me();
        ZonedDateTime now = clock.instant().atZone(clock.getZone()).truncatedTo(ChronoUnit.SECONDS);
        LocalDate date = now.toLocalDate();
        DaySchedules.ScheduledDay day = schedules.forPerson(person, date);
        DayHistory.Day today = DayHistory.today(day, records.findByPersonIdAndWorkDateOrderByShiftIdx(person, date));

        Optional<ScanEvent> last = scans.findFirstByPersonIdAndReceivedAtGreaterThanEqualOrderByReceivedAtDesc(person,
                date.atStartOfDay(clock.getZone()).toInstant());
        Optional<ScanEvent> outside = last.filter(e -> OUTSIDE_AREA.equals(e.getResult()));
        Optional<MissingCheckOut> missing = day.kind() == DaySchedules.Kind.WORK ? missingCheckOut(person, date) : Optional.empty();
        TodayStates.Card card = TodayStates.of(day, today.lines(), now.toInstant(), clock.getZone(), outside.isPresent(), missing.isPresent());

        ShiftViews.PersonDay mine = new ShiftViews.PersonDay(person, today);
        List<HistoryViews.Shift> views = shiftViews.batch(List.of(mine)).shifts(mine);
        List<Shift> shifts = IntStream.range(0, views.size()).mapToObj(i -> mapper.shift(views.get(i), card.phases().get(i))).toList();
        State state = card.state();
        return new Today(date, now.toOffsetDateTime(), day.kind().name(), state, shifts.isEmpty() ? null : shifts.get(card.current()).shiftIdx(),
                day.changed(), day.label(),
                day.holiday() == null ? null : new Holiday(day.holiday().name(), day.holiday().kind()),
                day.kind() == DaySchedules.Kind.LEAVE ? leave(person, day) : null,
                shifts, mapper.gate(ShiftViews.gateOf(views)),
                state == State.OUTSIDE_AREA ? outside.map(TodayCardService::outside).orElse(null) : null,
                state == State.MISSING_CHECK_OUT ? missing.orElse(null) : null);
    }

    @Override
    @Transactional(readOnly = true)
    public Month month() {
        UUID person = me();
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        YearMonth month = YearMonth.from(today);
        Map<Status, Long> count = history.history(person, month.atDay(1), today, today).stream()
                .collect(Collectors.groupingBy(DayHistory.Day::status, Collectors.counting()));
        int present = n(count, Status.ON_TIME) + n(count, Status.EARLY);
        int late = n(count, Status.LATE);
        int absent = n(count, Status.ABSENT) + n(count, Status.MISSING);
        int leave = n(count, Status.LEAVE);
        int working = (int) schedules.forPerson(person, month.atDay(1), month.atEndOfMonth()).stream()
                .filter(d -> d.kind() == DaySchedules.Kind.WORK || d.kind() == DaySchedules.Kind.LEAVE).count();
        return new Month(month, present, late, absent, leave, working, present + late + absent + leave);
    }

    // ------------------------------------------------------------------ parts of the card

    /**
     * The last day before today with records (at most {@value #MISSING_LOOKBACK_DAYS} days back): its first shift
     * checked in and never out, unless a correction was already asked for that check-out (and not cancelled).
     */
    private Optional<MissingCheckOut> missingCheckOut(UUID person, LocalDate today) {
        List<AttendanceRecord> before = records.findByPersonIdAndWorkDateBetweenOrderByWorkDateDescShiftIdxAsc(person,
                today.minusDays(MISSING_LOOKBACK_DAYS), today.minusDays(1));
        if (before.isEmpty()) {
            return Optional.empty();
        }
        LocalDate lastDay = before.getFirst().getWorkDate();
        return before.stream()
                .filter(r -> r.getWorkDate().equals(lastDay) && r.getInAt() != null && r.getOutAt() == null)
                .min(Comparator.comparingInt(AttendanceRecord::getShiftIdx))
                .filter(r -> approvals.latest(Approvals.Type.CORRECTION,
                                CorrectionService.subject(person, lastDay, r.getShiftIdx(), CorrectionService.Field.CHECK_OUT))
                        .map(a -> a.status() == Approvals.Status.CANCELLED).orElse(true))
                .map(r -> new MissingCheckOut(lastDay, r.getShiftIdx(), r.getShiftName(), r.getInAt(), r.getId()));
    }

    /** The leave days in a row from today (days off and holidays between do not end it), and the working day after. */
    private Leave leave(UUID person, DaySchedules.ScheduledDay today) {
        int days = 0;
        LocalDate backOn = null;
        for (DaySchedules.ScheduledDay d : schedules.forPerson(person, today.date(), today.date().plusDays(LEAVE_LOOKAHEAD_DAYS))) {
            if (d.kind() == DaySchedules.Kind.LEAVE) {
                days++;
            } else if (d.kind() == DaySchedules.Kind.WORK) {
                backOn = d.date();
                break;
            }
        }
        DaySchedules.Leave leave = today.leave();
        return new Leave(leave == null ? null : leave.type(), leave == null || leave.wholeDay(), days, backOn);
    }

    private static Outside outside(ScanEvent e) {
        Map<String, Object> d = e.getDetails();
        return new Outside(number(d.get("distanceKm")), number(d.get("radiusKm")), d.get("areaName") instanceof String s ? s : null,
                e.getReceivedAt());
    }

    private static Double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static int n(Map<Status, Long> count, Status s) {
        return count.getOrDefault(s, 0L).intValue();
    }

    private static UUID me() {
        UUID person = CurrentUser.require().personId();
        if (person == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return person;
    }
}
