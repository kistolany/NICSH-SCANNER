package kh.cmt.approvals.web;

import java.util.List;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.application.ApprovalInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web and the admin app: the one approvals inbox (step 10.1).
 * <pre>
 * GET  /api/v1/admin/approvals?status=pending|done&amp;type=LEAVE&amp;group=leave|correction|lateEarly|device
 *                                               {status, counts {LEAVE: 2…}, groups {leave: 2…}, total, items}
 * GET  /api/v1/admin/approvals/{id}             {item, history {LATE_REASON: 3…} (this month), facts}
 * POST /api/v1/admin/approvals/{id}/approve     {comment?}  → the item, undoUntil (403 APPROVAL_LIMIT, 409 ALREADY_DECIDED)
 * POST /api/v1/admin/approvals/{id}/reject      {comment}   → the item, undoUntil
 * POST /api/v1/admin/approvals/{id}/undo                    → the item, waiting again (409 TOO_LATE)
 * POST /api/v1/admin/approvals/bulk-approve     {ids}       → {approved [items], refused [{id, code, reason?, escalateTo?}]}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/approvals")
@RequiredArgsConstructor
class ApprovalController {

    private final ApprovalInboxService service;

    record Decision(String comment) {
    }

    record Ids(List<UUID> ids) {
    }

    @GetMapping
    ApprovalInboxService.Inbox list(@RequestParam(defaultValue = "pending") String status, @RequestParam(required = false) Approvals.Type type,
            @RequestParam(required = false) String group) {
        return service.list(status, type, group);
    }

    @GetMapping("/{id}")
    ApprovalInboxService.Detail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/approve")
    ApprovalInboxService.Item approve(@PathVariable UUID id, @RequestBody(required = false) Decision body) {
        return service.approve(id, body == null ? null : body.comment());
    }

    @PostMapping("/{id}/reject")
    ApprovalInboxService.Item reject(@PathVariable UUID id, @RequestBody(required = false) Decision body) {
        return service.reject(id, body == null ? null : body.comment());
    }

    @PostMapping("/{id}/undo")
    ApprovalInboxService.Item undo(@PathVariable UUID id) {
        return service.undo(id);
    }

    @PostMapping("/bulk-approve")
    ApprovalInboxService.Bulk bulkApprove(@RequestBody(required = false) Ids body) {
        return service.approveAll(body == null ? null : body.ids());
    }
}
