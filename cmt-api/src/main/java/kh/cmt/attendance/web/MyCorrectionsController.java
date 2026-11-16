package kh.cmt.attendance.web;

import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.application.CorrectionService;
import kh.cmt.attendance.application.RecordCommands;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: "Request correction".
 * <pre>
 * POST /api/v1/app/attendance/corrections  {date, shiftIdx, field CHECK_IN|CHECK_OUT, time HH:mm, reason, attachmentFileId}
 *      201 {id, type CORRECTION, status PENDING, reason, details {date, shiftIdx, field, recordedTime, requestedTime, …}, requestedAt}
 *      200 the same — the request still waiting for that shift and field was changed
 *      400 VALIDATION_ERROR {errors [{field, rule}]} (rules: NotNull, Pattern, Future, OneOf, NotBlank, Size, BeforeCheckIn,
 *          AfterCheckOut, Unchanged), 422 NOT_A_WORKING_DAY {kind}, 409 ALREADY_DECIDED {decision}, FILE_NOT_READY, FILE_IN_USE
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/app/attendance/corrections")
@RequiredArgsConstructor
class MyCorrectionsController {

    private final CorrectionService corrections;

    @PostMapping
    ResponseEntity<Approvals.View> request(@RequestBody RecordCommands.Correction body) {
        Approvals.Sent sent = corrections.request(body);
        return ResponseEntity.status(sent.created() ? HttpStatus.CREATED : HttpStatus.OK).body(sent.approval());
    }
}
