package kh.cmt.attendance.web;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import kh.cmt.attendance.application.AdminAttendanceService;
import kh.cmt.attendance.application.AdminAttendanceViews;
import kh.cmt.attendance.application.HistoryViews;
import kh.cmt.attendance.application.RecordCommands;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin web: Staff attendance (permissions: Attendance view / edit / export).
 * <pre>
 * GET  /api/v1/admin/attendance/day?date=&amp;department=&amp;role=&amp;status=&amp;q=   → {date, counts, rows}
 * GET  /api/v1/admin/attendance/month?month=yyyy-MM&amp;department=&amp;role=&amp;q=  → {month, dates, rows}
 * PUT  /api/v1/admin/attendance/records/{id}  {checkIn, checkOut, reason, version}  → the shift ("Edited by admin")
 * POST /api/v1/admin/attendance/records       {personId, date, shiftIdx, checkIn, checkOut, reason} → 201 ("Manual")
 * GET  /api/v1/admin/attendance/export.xlsx?view=day|month&amp;date=&amp;month=&amp;department=&amp;role=&amp;status=&amp;q=&amp;personId=
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/attendance")
@RequiredArgsConstructor
class AdminAttendanceController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final AdminAttendanceService attendance;

    @GetMapping("/day")
    AdminAttendanceViews.Day day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID department, @RequestParam(required = false) String role,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return attendance.day(date, new AdminAttendanceService.Filter(department, role, q, status));
    }

    @GetMapping("/month")
    AdminAttendanceViews.Month month(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) UUID department, @RequestParam(required = false) String role, @RequestParam(required = false) String q) {
        return attendance.month(month, new AdminAttendanceService.Filter(department, role, q, null));
    }

    @PutMapping("/records/{id}")
    HistoryViews.Shift edit(@PathVariable UUID id, @RequestBody RecordCommands.EditRecord body) {
        return attendance.edit(id, body);
    }

    @PostMapping("/records")
    ResponseEntity<HistoryViews.Shift> create(@RequestBody RecordCommands.NewRecord body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(attendance.create(body));
    }

    @GetMapping("/export.xlsx")
    ResponseEntity<byte[]> export(@RequestParam(defaultValue = "day") String view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) UUID department, @RequestParam(required = false) String role,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q, @RequestParam(required = false) UUID personId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String name = attendance.export(new AdminAttendanceService.Export("month".equals(view) ? "month" : "day", date, month,
                new AdminAttendanceService.Filter(department, role, q, status), personId), out);
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(out.toByteArray());
    }
}
