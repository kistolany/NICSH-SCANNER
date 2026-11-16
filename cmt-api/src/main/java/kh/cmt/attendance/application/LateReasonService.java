package kh.cmt.attendance.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Send reason to admin" after a late check-in (step 7.4): the reason is kept on the record (admin attendance shows it)
 * and sent as a LATE_REASON request to the approvals inbox. Sending again while it waits changes the reason; once an admin
 * decided it (phase 10) → {@code ALREADY_DECIDED}. Only the person's own record, and only a late one ({@code NOT_LATE}).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LateReasonService {

    static final int MAX_REASON = 300;
    static final String SUBJECT = "ATTENDANCE_RECORD";

    private final AttendanceRecordRepository records;
    private final Approvals approvals;

    @Transactional
    public Approvals.Sent send(UUID recordId, RecordCommands.LateReason c) {
        UUID person = CurrentUser.require().personId();
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", "reason", "rule", reason.isEmpty() ? "NotBlank" : "Size")));
        }
        AttendanceRecord r = records.findById(recordId)
                .filter(x -> person != null && x.getPersonId().equals(person))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (r.getLateMinutes() == 0) {
            throw new ApiException(ErrorCode.NOT_LATE);
        }
        Approvals.Sent sent = approvals.request(new Approvals.Request(Approvals.Type.LATE_REASON, SUBJECT, r.getId(), person, reason, details(r)));
        r.lateReason(reason);
        records.saveAndFlush(r);
        return sent;
    }

    /** What the inbox card shows: "Late 12 min · Check-in 07:57". */
    private static Map<String, Object> details(AttendanceRecord r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("workDate", r.getWorkDate().toString());
        d.put("shiftIdx", r.getShiftIdx());
        if (r.getShiftName() != null) {
            d.put("shiftName", r.getShiftName());
        }
        d.put("shiftStart", r.getPlannedStart().toString());
        d.put("checkInAt", r.getInAt().toString());
        d.put("lateMinutes", r.getLateMinutes());
        return d;
    }
}
