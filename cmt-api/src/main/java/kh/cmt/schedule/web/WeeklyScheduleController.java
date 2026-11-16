package kh.cmt.schedule.web;

import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.schedule.application.WeeklyScheduleCommands;
import kh.cmt.schedule.application.WeeklyScheduleService;
import kh.cmt.schedule.application.WeeklyScheduleView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Shifts &amp; schedules › Weekly schedules. {@code target} = everyone (default) | department | person, with
 * {@code id} for the last two.
 * <pre>
 * GET /api/v1/admin/schedules/weekly?target=&amp;id=            its versions and what applies today
 * PUT /api/v1/admin/schedules/weekly?target=&amp;id=            {effectiveFrom, days [{weekday, templateId}], inherit}
 * GET /api/v1/admin/schedules/weekly/preview?target=&amp;id=&amp;date=   the grouped week ("Monday – Friday · …")
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/schedules/weekly")
class WeeklyScheduleController {

    private final WeeklyScheduleService service;

    WeeklyScheduleController(WeeklyScheduleService service) {
        this.service = service;
    }

    @GetMapping
    WeeklyScheduleView.TargetSchedule get(@RequestParam(required = false) String target, @RequestParam(required = false) UUID id) {
        return service.get(target, id);
    }

    @PutMapping
    WeeklyScheduleView.TargetSchedule save(@RequestParam(required = false) String target, @RequestParam(required = false) UUID id,
            @RequestBody WeeklyScheduleCommands.Save command) {
        return service.save(target, id, command);
    }

    @GetMapping("/preview")
    WeeklyScheduleView.Preview preview(@RequestParam(required = false) String target, @RequestParam(required = false) UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.preview(target, id, date);
    }
}
