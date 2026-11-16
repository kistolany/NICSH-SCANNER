package kh.cmt.schedule.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.application.DateChangeCommands;
import kh.cmt.schedule.application.DateChangeService;
import kh.cmt.schedule.application.DateChangeView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Shifts &amp; schedules › Date changes.
 * <pre>
 * GET    /api/v1/admin/schedules/date-changes?from=&amp;to=   by date (from today, a year ahead, when left out)
 * POST   /api/v1/admin/schedules/date-changes              {dates, target, targetId, templateId (none = Off), label}
 * DELETE /api/v1/admin/schedules/date-changes/{id}         today or later only (IN_THE_PAST otherwise)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/schedules/date-changes")
class DateChangeController {

    private final DateChangeService service;

    DateChangeController(DateChangeService service) {
        this.service = service;
    }

    @GetMapping
    List<DateChangeView.Change> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.listChanges(from, to);
    }

    @PostMapping
    ResponseEntity<DateChangeView.Saved> create(@RequestBody DateChangeCommands.Create command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createChanges(command));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteChange(id);
        return ResponseEntity.noContent().build();
    }
}
