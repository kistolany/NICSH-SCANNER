package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.leave.api.LeaveApproved;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.events.OutboxHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Leave was approved (step 9.2): for its dates up to today, the shifts the end-of-day job already marked ABSENT and
 * that are now on leave become LEAVE, and those days are written again in the daily summary. Days still to come need
 * nothing: the schedule already says leave.
 */
@Component
@RequiredArgsConstructor
class LeaveApprovedHandler implements OutboxHandler<LeaveApproved> {

    private final AttendanceRecordRepository records;
    private final DaySchedules schedules;
    private final DailySummaries summaries;
    private final Clock clock;

    @Override
    public String type() {
        return LeaveApproved.TYPE;
    }

    @Override
    public Class<LeaveApproved> payloadType() {
        return LeaveApproved.class;
    }

    @Override
    public void handle(LeaveApproved e) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        for (LocalDate day : e.dates()) {
            if (day.isAfter(today)) {
                continue;
            }
            Set<Integer> away = schedules.forPerson(e.personId(), day).shifts().stream().filter(DaySchedules.Shift::onLeave)
                    .map(DaySchedules.Shift::position).collect(Collectors.toSet());
            records.findByPersonIdAndWorkDateOrderByShiftIdx(e.personId(), day).stream()
                    .filter(r -> away.contains(r.getShiftIdx())).forEach(AttendanceRecord::onLeave);
            records.flush();
            summaries.refresh(e.personId(), day);
        }
    }
}
