package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import kh.cmt.approvals.api.ApprovalFacts;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The request detail sheet's month (step 10.1), for every type: days this month the person was late ("Late 3 times"),
 * left early, was absent (or did not check out), was on leave — and how many late / early days an admin excused.
 */
@Component
@RequiredArgsConstructor
class AttendanceApprovalFacts implements ApprovalFacts {

    private final AttendanceRecordRepository records;
    private final Clock clock;

    @Override
    public Map<String, Object> facts(Approvals.Type type, UUID personId, UUID subjectId, Map<String, Object> details) {
        LocalDate today = LocalDate.now(clock);
        List<AttendanceRecord> month = records.findByPersonIdAndWorkDateBetweenOrderByWorkDateDescShiftIdxAsc(personId, today.withDayOfMonth(1),
                today);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("lateThisMonth", days(month, r -> r.getLateMinutes() > 0));
        facts.put("lateExcusedThisMonth", days(month, r -> r.getLateMinutes() > 0 && r.isLateExcused()));
        facts.put("earlyThisMonth", days(month, r -> r.getEarlyMinutes() > 0));
        facts.put("earlyExcusedThisMonth", days(month, r -> r.getEarlyMinutes() > 0 && r.isEarlyExcused()));
        facts.put("absentThisMonth", days(month, r -> r.getStatus() == AttendanceRecord.Status.ABSENT || r.getStatus() == AttendanceRecord.Status.MISSING));
        facts.put("leaveThisMonth", days(month, r -> r.getStatus() == AttendanceRecord.Status.LEAVE));
        return facts;
    }

    private static long days(List<AttendanceRecord> month, Predicate<AttendanceRecord> which) {
        return month.stream().filter(which).map(AttendanceRecord::getWorkDate).distinct().count();
    }
}
