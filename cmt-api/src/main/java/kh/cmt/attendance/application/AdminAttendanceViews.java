package kh.cmt.attendance.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** What the admin web's Staff attendance page shows (step 7.7). */
public final class AdminAttendanceViews {

    private AdminAttendanceViews() {
    }

    public record Person(UUID id, String staffId, LocalizedName name, String role, UUID departmentId) {
    }

    /**
     * GET /admin/attendance/day: one row per person who works that day (or has a record of it). {@code counts}: the
     * status chips over the rows the department / role / search keep ({@code ALL} + one per status).
     */
    public record Day(LocalDate date, Map<String, Integer> counts, List<DayRow> rows) {
    }

    /**
     * {@code status} ON_TIME | LATE | EARLY | ABSENT | LEAVE | NOT_YET | OUTSIDE_AREA | MISSING | HOLIDAY; the shifts
     * with their times (two lines on a two-shift day); {@code flags} MANUAL | EDITED | OFFLINE.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DayRow(Person person, String status, List<HistoryViews.Shift> shifts, int workedMinutes, int lateMinutes, int earlyMinutes,
            HistoryViews.Gate gate, List<String> flags, LocalizedName holidayName, String label) {
    }

    /**
     * GET /admin/attendance/month: person × day. {@code days} per row: the day's status (as the day view gives it) or
     * null (a day off, without a schedule, or still to come); {@code present} = days with a check-in, {@code late},
     * {@code absent}, {@code workedMinutes}.
     */
    public record Month(@JsonFormat(pattern = "yyyy-MM") YearMonth month, List<LocalDate> dates, List<MonthRow> rows) {
    }

    public record MonthRow(Person person, List<String> days, int present, int late, int absent, int workedMinutes) {
    }
}
