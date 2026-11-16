package kh.cmt.leave.web;

import java.util.List;
import java.util.UUID;
import kh.cmt.leave.application.LeaveBalanceService;
import kh.cmt.leave.application.LeaveCommands;
import kh.cmt.leave.application.LeaveRequestService;
import kh.cmt.leave.application.LeaveViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff app's Leave page.
 * <pre>
 * GET    /api/v1/app/leave/balances                {year, balances [{typeId, type, name, color, halfDays, total, used, left, pending}]}
 * GET    /api/v1/app/leave/preview?typeId=&amp;startDate=&amp;endDate=&amp;half=   the days and the balance before sending
 * GET    /api/v1/app/leave/requests                newest first
 * POST   /api/v1/app/leave/requests                {typeId, startDate, endDate, half AM|PM, reason, attachmentFileId} → 201
 * GET    /api/v1/app/leave/requests/{id}           {request, timeline}
 * DELETE /api/v1/app/leave/requests/{id}           take back while it waits → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/app/leave")
@RequiredArgsConstructor
class MyLeaveController {

    private final LeaveBalanceService balances;
    private final LeaveRequestService requests;

    @GetMapping("/balances")
    LeaveViews.Mine balances() {
        return balances.mineView();
    }

    @GetMapping("/preview")
    LeaveViews.Preview preview(@RequestParam(required = false) UUID typeId, @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate, @RequestParam(required = false) String half) {
        return requests.preview(new LeaveCommands.Request(typeId, startDate, endDate, half, null, null));
    }

    @GetMapping("/requests")
    List<LeaveViews.Request> mine() {
        return requests.mine();
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    LeaveViews.Request request(@RequestBody LeaveCommands.Request command) {
        return requests.request(command);
    }

    @GetMapping("/requests/{id}")
    LeaveViews.RequestDetail one(@PathVariable UUID id) {
        return requests.one(id);
    }

    @DeleteMapping("/requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id) {
        requests.cancel(id);
    }
}
