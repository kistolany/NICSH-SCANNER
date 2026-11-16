package kh.cmt.leave.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.leave.application.LeaveCommands;
import kh.cmt.leave.application.LeaveTypeService;
import kh.cmt.leave.application.LeaveViews;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Leave &amp; holidays › Leave types.
 * <pre>
 * GET    /api/v1/admin/leave-types?status=active|inactive
 * POST   /api/v1/admin/leave-types               {name {km, en, ar}, daysPerYear, halfDays, attachment NONE|OVER_ONE_DAY|ALWAYS, color} → 201
 * PUT    /api/v1/admin/leave-types/{id}          the same + version
 * DELETE /api/v1/admin/leave-types/{id}?reason=  move to inactive → 204
 * POST   /api/v1/admin/leave-types/{id}/restore  back to active
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/leave-types")
@RequiredArgsConstructor
class LeaveTypeController {

    private final LeaveTypeService service;

    @GetMapping
    List<LeaveViews.Type> list(@RequestParam(defaultValue = "active") String status) {
        return service.list(switch (status) {
            case "active" -> false;
            case "inactive" -> true;
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", "status", "rule", "OneOf")));
        });
    }

    @PostMapping
    ResponseEntity<LeaveViews.Type> create(@RequestBody LeaveCommands.Type command) {
        LeaveViews.Type created = service.create(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/leave-types/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    LeaveViews.Type update(@PathVariable UUID id, @RequestBody LeaveCommands.Type command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> inactivate(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        service.inactivate(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    LeaveViews.Type restore(@PathVariable UUID id) {
        return service.restore(id);
    }
}
