package kh.cmt.leave.web;

import java.util.UUID;
import kh.cmt.leave.application.LeaveBalanceService;
import kh.cmt.leave.application.LeaveCommands;
import kh.cmt.leave.application.LeaveViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Leave &amp; holidays › Balances ({@code year} this year by default).
 * <pre>
 * GET  /api/v1/admin/leave-balances?year=                     {year, types, rows [{person, balances [{typeId, entitled,
 *                                                              adjusted, total, used, left}]}]}
 * GET  /api/v1/admin/leave-balances/{personId}?year=          {year, types, row, adjustments}
 * POST /api/v1/admin/leave-balances/{personId}/adjust         {typeId, year, days ±, reason} → 201 the person's balances
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/leave-balances")
@RequiredArgsConstructor
class LeaveBalanceController {

    private final LeaveBalanceService service;

    @GetMapping
    LeaveViews.Table table(@RequestParam(required = false) Integer year) {
        return service.table(year);
    }

    @GetMapping("/{personId}")
    LeaveViews.PersonBalances person(@PathVariable UUID personId, @RequestParam(required = false) Integer year) {
        return service.person(personId, year);
    }

    @PostMapping("/{personId}/adjust")
    @ResponseStatus(HttpStatus.CREATED)
    LeaveViews.PersonBalances adjust(@PathVariable UUID personId, @RequestBody LeaveCommands.Adjust command) {
        return service.adjust(personId, command);
    }
}
