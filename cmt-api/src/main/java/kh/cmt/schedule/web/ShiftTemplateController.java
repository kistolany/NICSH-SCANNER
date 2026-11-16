package kh.cmt.schedule.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.application.ShiftTemplateCommands;
import kh.cmt.schedule.application.ShiftTemplateService;
import kh.cmt.schedule.application.ShiftTemplateView;
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
 * Admin Web, Shifts &amp; schedules › Shift templates.
 * <pre>
 * GET    /api/v1/admin/shift-templates?status=active|inactive
 * GET    /api/v1/admin/shift-templates/{id}
 * POST   /api/v1/admin/shift-templates                 {name, shifts [{name, start, end}], graceMinutes, earlyCheckOutNeedsReason, color}
 * PUT    /api/v1/admin/shift-templates/{id}            the same + version
 * DELETE /api/v1/admin/shift-templates/{id}?reason=    move to inactive
 * POST   /api/v1/admin/shift-templates/{id}/restore
 * DELETE /api/v1/admin/shift-templates/{id}/forever    Super Admin, inactive only, IN_USE while schedules use it
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/shift-templates")
class ShiftTemplateController {

    private final ShiftTemplateService service;

    ShiftTemplateController(ShiftTemplateService service) {
        this.service = service;
    }

    @GetMapping
    List<ShiftTemplateView> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    ShiftTemplateView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<ShiftTemplateView> create(@RequestBody ShiftTemplateCommands.Save command) {
        ShiftTemplateView created = service.create(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/shift-templates/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    ShiftTemplateView update(@PathVariable UUID id, @RequestBody ShiftTemplateCommands.Save command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> inactivate(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        service.inactivate(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    ShiftTemplateView restore(@PathVariable UUID id) {
        return service.restore(id);
    }

    @DeleteMapping("/{id}/forever")
    ResponseEntity<Void> deleteForever(@PathVariable UUID id) {
        service.deleteForever(id);
        return ResponseEntity.noContent().build();
    }
}
