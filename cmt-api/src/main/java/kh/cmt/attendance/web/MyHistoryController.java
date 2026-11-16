package kh.cmt.attendance.web;

import java.time.LocalDate;
import java.time.YearMonth;
import kh.cmt.attendance.application.HistoryViews;
import kh.cmt.attendance.application.MyHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: one's own attendance history.
 * <pre>
 * GET /api/v1/app/attendance?range=week|month|last|custom&amp;from=&amp;to=&amp;status=ALL|ON_TIME|LATE|EARLY|ABSENT|LEAVE
 *      → {from, to, status, summary {present, late, early, absent, leave, totalMinutes}, days [Day]} (newest first)
 * GET /api/v1/app/attendance/{yyyy-MM-dd}       → Day (404: a day off, without a schedule, or still to come)
 * GET /api/v1/app/attendance/calendar?month=yyyy-MM → {month, days [{date, status}]}
 * </pre>
 * The PDF export is made by the app from the list (SPEC §4.7).
 */
@RestController
@RequestMapping("/api/v1/app/attendance")
@RequiredArgsConstructor
class MyHistoryController {

    private final MyHistoryService history;

    @GetMapping
    HistoryViews.Page list(@RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status) {
        return history.list(range, from, to, status);
    }

    @GetMapping("/calendar")
    HistoryViews.Calendar calendar(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return history.calendar(month);
    }

    @GetMapping("/{date:\\d{4}-\\d{2}-\\d{2}}")
    HistoryViews.Day day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return history.day(date);
    }
}
