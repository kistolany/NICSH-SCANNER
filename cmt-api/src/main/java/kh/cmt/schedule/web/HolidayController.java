package kh.cmt.schedule.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.application.DateChangeCommands;
import kh.cmt.schedule.application.DateChangeService;
import kh.cmt.schedule.application.DateChangeView;
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
 * Admin Web, Leave &amp; holidays › Holiday calendar.
 * <pre>
 * GET    /api/v1/admin/holidays?year=        holidays with a day in the year (this year when left out)
 * GET    /api/v1/admin/holidays/{id}
 * POST   /api/v1/admin/holidays              {name {km, en, ar}, kind NATIONAL|SCHOOL, startDate, endDate, departmentId}
 * PUT    /api/v1/admin/holidays/{id}         the same + version; only before it starts (IN_THE_PAST otherwise)
 * DELETE /api/v1/admin/holidays/{id}         only before it starts
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/holidays")
class HolidayController {

    private final DateChangeService service;

    HolidayController(DateChangeService service) {
        this.service = service;
    }

    @GetMapping
    List<DateChangeView.HolidayItem> list(@RequestParam(required = false) Integer year) {
        return service.listHolidays(year);
    }

    @GetMapping("/{id}")
    DateChangeView.HolidayItem get(@PathVariable UUID id) {
        return service.getHoliday(id);
    }

    @PostMapping
    ResponseEntity<DateChangeView.HolidayItem> create(@RequestBody DateChangeCommands.SaveHoliday command) {
        DateChangeView.HolidayItem created = service.createHoliday(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/holidays/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    DateChangeView.HolidayItem update(@PathVariable UUID id, @RequestBody DateChangeCommands.SaveHoliday command) {
        return service.updateHoliday(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteHoliday(id);
        return ResponseEntity.noContent().build();
    }
}
