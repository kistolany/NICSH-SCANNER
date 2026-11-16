package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.DailySummaryStore;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.security.SchoolContext;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code attendance_daily_summary} in line with the day as the history shows it ({@link DayHistory}): written by
 * the end-of-day job for everyone, and again for one person whenever one of their records changes (a scan, an edit, a
 * correction applied, a late / early reason decided). Late and early minutes an admin excused do not count. A day with
 * nothing to show has no row.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class DailySummaries {

    private final AttendanceRecordRepository records;
    private final DaySchedules schedules;
    private final DailySummaryStore store;
    private final SchoolContext schools;
    private final Clock clock;

    /** One person's day, from the database. */
    void refresh(UUID person, LocalDate date) {
        write(person, schedules.forPerson(person, date), records.findByPersonIdAndWorkDateOrderByShiftIdx(person, date), false);
    }

    /** One person's day from what the caller already has; {@code closed}: written by the end-of-day job. */
    void write(UUID person, DaySchedules.ScheduledDay schedule, List<AttendanceRecord> recs, boolean closed) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        Optional<DayHistory.Day> day = DayHistory.of(today, schedule, recs);
        if (day.isEmpty()) {
            store.remove(person, schedule.date());
            return;
        }
        DayHistory.Day d = day.get();
        int required = schedule.kind() == DaySchedules.Kind.WORK
                ? (int) schedule.shifts().stream().filter(s -> !s.onLeave()).mapToLong(DaySchedules.Shift::minutes).sum() : 0;
        store.save(new DailySummaryStore.Summary(schools.currentSchoolId(), person, schedule.date(), d.status().name(), required, d.workedMinutes(),
                d.countedLateMinutes(), d.countedEarlyMinutes(), d.status() == DayHistory.Status.ABSENT, d.status() == DayHistory.Status.LEAVE, closed),
                clock.instant());
    }
}
