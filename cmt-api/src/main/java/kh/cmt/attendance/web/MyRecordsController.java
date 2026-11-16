package kh.cmt.attendance.web;

import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.application.LateReasonService;
import kh.cmt.attendance.application.RecordCommands;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: one's own attendance records.
 * <pre>
 * POST /api/v1/app/attendance/records/{id}/late-reason  {reason}
 *      201 {id, type LATE_REASON, status PENDING, reason, requestedAt} — sent to the approvals inbox
 *      200 the same — the request still waiting was changed
 *      400 VALIDATION_ERROR (reason blank / over 300), 404 not one's own record, 409 NOT_LATE, 409 ALREADY_DECIDED {decision}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/app/attendance/records")
@RequiredArgsConstructor
class MyRecordsController {

    private final LateReasonService lateReasons;

    @PostMapping("/{id}/late-reason")
    ResponseEntity<Approvals.View> lateReason(@PathVariable UUID id, @RequestBody RecordCommands.LateReason body) {
        Approvals.Sent sent = lateReasons.send(id, body);
        return ResponseEntity.status(sent.created() ? HttpStatus.CREATED : HttpStatus.OK).body(sent.approval());
    }
}
