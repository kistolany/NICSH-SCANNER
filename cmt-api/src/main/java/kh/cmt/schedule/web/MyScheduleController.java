package kh.cmt.schedule.web;

import java.time.LocalDate;
import kh.cmt.schedule.application.ScheduleResolver;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: the signed-in person's schedule.
 * <pre>
 * GET /api/v1/app/me/schedule/today          the Today card: kind, shifts with times and grace, holiday / leave / change,
 *                                            serverTime
 * GET /api/v1/app/me/schedule/week?date=     My schedule: the week around the date (today) as it will be, the usual week,
 *                                            and the changes from today on
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/app/me/schedule")
class MyScheduleController {

    private final ScheduleResolver resolver;

    MyScheduleController(ScheduleResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/today")
    ScheduleResolver.MyDay today() {
        return resolver.myToday();
    }

    @GetMapping("/week")
    ScheduleResolver.MyWeek week(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return resolver.myWeek(date);
    }
}
