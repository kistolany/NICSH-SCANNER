package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.attendance.api.MissingCheckOut;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.DayCloseStore;
import kh.cmt.org.api.MissingCheckOutRule;
import kh.cmt.org.api.SchoolSettingsQueries;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.security.SchoolContext;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The end of a school day (step 7.8), for the current school ({@code SchoolContext}): for every active person,
 * <ul>
 * <li>a check-in without a check-out follows the school's rule ({@link MissingCheckOutRule}): ask for a correction →
 * MISSING and the person is told ({@link MissingCheckOut}); flag only → MISSING; close automatically → checked out at
 * the shift's planned end;</li>
 * <li>a shift of a working day nobody scanned (not on leave; holidays and days off have no shifts) → an ABSENT
 * record;</li>
 * <li>the daily summary is written.</li>
 * </ul>
 * Closing a day again changes nothing that is already done (no second ABSENT record, no second message). Days are due
 * from the day after the last one closed — at most {@value #CATCH_UP_DAYS} days back, so a stopped server catches up —
 * and today once past the closing time ({@code cmt.attendance.day-close.time}, 23:30); a school never closed starts
 * with today (no absences are made for the days before the system was used).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DayCloseService {

    static final int CATCH_UP_DAYS = 7;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** What one close did. */
    public record Result(LocalDate date, int people, int absent, int missing) {
    }

    private final PeopleQueries people;
    private final AttendanceRecordRepository records;
    private final DaySchedules schedules;
    private final SchoolSettingsQueries settings;
    private final DailySummaries summaries;
    private final DayCloseStore closes;
    private final Outbox outbox;
    private final SchoolContext schoolContext;
    private final Clock clock;

    @Value("${cmt.attendance.day-close.time:23:30}")
    private LocalTime closeTime;

    /** The days to close now, oldest first. */
    public List<LocalDate> due() {
        ZoneId zone = clock.getZone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate last = !now.isBefore(today.atTime(closeTime).atZone(zone).toInstant()) ? today : today.minusDays(1);
        LocalDate from = closes.lastClosed(schoolContext.currentSchoolId()).map(d -> d.plusDays(1))
                .map(d -> d.isBefore(today.minusDays(CATCH_UP_DAYS)) ? today.minusDays(CATCH_UP_DAYS) : d)
                .orElse(today);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(last); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    @Transactional
    public Result close(LocalDate day) {
        ZoneId zone = clock.getZone();
        MissingCheckOutRule rule = settings.attendanceRules().missingCheckOutRule();
        Map<UUID, List<AttendanceRecord>> byPerson = records.findByWorkDateBetween(day, day).stream()
                .collect(Collectors.groupingBy(AttendanceRecord::getPersonId));
        List<PeopleQueries.PersonPlace> everyone = people.activePeople();
        int absent = 0;
        int missing = 0;
        for (PeopleQueries.PersonPlace p : everyone) {
            DaySchedules.ScheduledDay schedule = schedules.forPerson(p.id(), day);
            List<AttendanceRecord> recs = new ArrayList<>(byPerson.getOrDefault(p.id(), List.of()));
            List<String> forgot = new ArrayList<>();
            for (AttendanceRecord r : recs) {
                if (r.getInAt() == null || r.getOutAt() != null || r.getStatus() == AttendanceRecord.Status.MISSING) {
                    continue;
                }
                if (rule == MissingCheckOutRule.AUTO_CLOSE_AT_SHIFT_END) {
                    r.autoClose(zone);
                } else {
                    r.missingCheckOut();
                    if (rule == MissingCheckOutRule.ASK_CORRECTION_NEXT_DAY) {
                        forgot.add(HH_MM.format(r.getPlannedStart()) + " – " + HH_MM.format(r.getPlannedEnd()));
                    }
                }
                records.save(r);
                missing++;
            }
            if (schedule.kind() == DaySchedules.Kind.WORK) {
                for (DaySchedules.Shift s : schedule.shifts()) {
                    if (!s.onLeave() && recs.stream().noneMatch(r -> r.getShiftIdx() == s.position())) {
                        recs.add(records.save(AttendanceRecord.without(p.id(), day,
                                new AttendanceRecord.Plan(s.position(), s.name(), s.start(), s.end(), s.graceMinutes(), s.earlyCheckOutNeedsReason()),
                                AttendanceRecord.Status.ABSENT)));
                        absent++;
                    }
                }
            }
            if (!forgot.isEmpty()) {
                outbox.add(MissingCheckOut.TYPE, new MissingCheckOut(p.id(), day, forgot));
            }
            summaries.write(p.id(), schedule, recs, true);
        }
        records.flush();
        closes.save(schoolContext.currentSchoolId(), day, clock.instant(), everyone.size(), absent, missing);
        return new Result(day, everyone.size(), absent, missing);
    }
}
