package kh.cmt.attendance.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** What the staff app's attendance history shows (step 7.5). */
public final class HistoryViews {

    private HistoryViews() {
    }

    /**
     * GET /app/attendance. {@code summary} counts every day of the range (the status filter only narrows {@code days}):
     * present = on time, late, early, absent (absent + missing check-out), leave, worked minutes.
     */
    public record Page(LocalDate from, LocalDate to, String status, Summary summary, List<Day> days) {
    }

    public record Summary(int present, int late, int early, int absent, int leave, int totalMinutes) {
    }

    /**
     * One day, newest first. {@code status} ON_TIME | LATE | EARLY | MISSING | ABSENT | LEAVE | HOLIDAY; minutes summed
     * over the shifts; {@code gate} where the day was checked in.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(LocalDate date, String status, List<Shift> shifts, int totalMinutes, int lateMinutes, int earlyMinutes, Gate gate,
            boolean editedByAdmin, LocalizedName holidayName, String label) {
    }

    /**
     * One shift: {@code status} ON_TIME | LATE | EARLY | MISSING | ABSENT | LEAVE | NOT_YET; times {@code HH:mm} as
     * planned that day; {@code lateReason} with where its request stands; {@code earlyReason} {code, note} and its request;
     * {@code lateExcused} / {@code earlyExcused}: an admin approved the reason ("Late 12 min · Excused": the minutes do not
     * count, so the status is not LATE / EARLY for them); {@code corrections} the latest request for its check-in and / or
     * check-out.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shift(int shiftIdx, String name, @JsonFormat(pattern = "HH:mm") LocalTime start, @JsonFormat(pattern = "HH:mm") LocalTime end,
            String status, Instant checkIn, Instant checkOut, Gate inGate, Gate outGate, int workedMinutes, int lateMinutes, int earlyMinutes,
            LateReason lateReason, EarlyReason earlyReason, Boolean lateExcused, Boolean earlyExcused, Edited edited, List<Correction> corrections,
            UUID recordId, Long version) {
    }

    /** A correction request: {@code field} CHECK_IN | CHECK_OUT, the time asked for, PENDING | APPROVED | REJECTED | CANCELLED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Correction(String field, String time, String status, Instant decidedAt, String comment) {
    }

    public record Gate(UUID id, String name) {
    }

    /** The late reason and its request: PENDING | APPROVED | REJECTED | CANCELLED (+ the admin's comment). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LateReason(String text, String status, Instant decidedAt, String comment) {
    }

    /**
     * "Leave early?" answer: the reason code (personal, sick, approved_by_admin, other) and the note; its request PENDING |
     * APPROVED | REJECTED (+ the admin's comment).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EarlyReason(String code, String note, String status, Instant decidedAt, String comment) {

        public EarlyReason(String code, String note) {
            this(code, note, null, null, null);
        }
    }

    /** "Edited by admin": when, why, and (for the admin pages) by whom. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edited(Instant at, String reason, UUID by, String byName) {

        public Edited(Instant at, String reason) {
            this(at, reason, null, null);
        }
    }

    /** GET /app/attendance/calendar: one coloured dot per day. */
    public record Calendar(@JsonFormat(pattern = "yyyy-MM") YearMonth month, List<Dot> days) {
    }

    public record Dot(LocalDate date, String status) {
    }
}
