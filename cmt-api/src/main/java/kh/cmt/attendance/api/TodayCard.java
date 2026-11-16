package kh.cmt.attendance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * The signed-in person's Today card and this month's numbers, for the app's Home (step 7.9). The server decides every
 * state (SPEC §6): the app only shows it.
 */
public interface TodayCard {

    Today today();

    Month month();

    /**
     * The card's state (SPEC §4.4, §4.5), decided in this order:
     * <ul>
     * <li>HOLIDAY · ON_LEAVE (every shift on leave) · DAY_OFF (a day off or no schedule).</li>
     * <li>Nothing scanned today: ABSENT (every shift is over) · OUTSIDE_AREA (the last scan was refused for it, see
     * {@code outside}) · MISSING_CHECK_OUT (the last working day was never checked out and no correction was asked, see
     * {@code missingCheckOut}) · NOT_CHECKED_IN.</li>
     * <li>Else by the shift shown ({@code current}): WORKING · BREAK (shift 1 finished, shift 2 to come) · OUTSIDE_AREA
     * (the next shift's check-in was refused for it) · NOT_CHECKED_IN (the next shift is still to come) · DONE (every
     * shift finished; "2 shifts completed") · EARLY_OUT (the one shift checked out early) · MISSED (the shift shown was
     * missed).</li>
     * </ul>
     */
    enum State { NOT_CHECKED_IN, WORKING, BREAK, DONE, EARLY_OUT, MISSED, OUTSIDE_AREA, MISSING_CHECK_OUT, ABSENT, ON_LEAVE, HOLIDAY, DAY_OFF }

    /** Where one shift stands today: LEAVE (on leave for it), UPCOMING, WORKING, DONE, EARLY (out early), MISSED (no scan by its end, or never checked out). */
    enum Phase { UPCOMING, WORKING, DONE, EARLY, MISSED, LEAVE }

    /**
     * Today. {@code kind} WORK | OFF | HOLIDAY | LEAVE | NO_SCHEDULE (as the schedule); {@code current} the shift the
     * card shows first (its {@code shiftIdx}); {@code changed}/{@code label} a date change applies ("Exam day");
     * {@code gate} where today was checked in.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Today(LocalDate date, OffsetDateTime serverTime, String kind, State state, Integer current, boolean changed, String label,
            Holiday holiday, Leave leave, List<Shift> shifts, Gate gate, Outside outside, MissingCheckOut missingCheckOut) {
    }

    /** {@code kind} NATIONAL | SCHOOL. */
    record Holiday(LocalizedName name, String kind) {
    }

    /** On leave today: the type, the leave days in a row from today, and the first working day after them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Leave(String type, boolean wholeDay, int days, LocalDate backOn) {
    }

    /**
     * One shift as planned today, how it stands and its record (if any). {@code status} as the history (ON_TIME | LATE |
     * EARLY | MISSING | ABSENT | LEAVE | NOT_YET); {@code lateReason} / {@code earlyReason} the reason sent and where its
     * request stands; {@code lateExcused} / {@code earlyExcused}: approved by an admin, the minutes do not count.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Shift(int shiftIdx, String name, @JsonFormat(pattern = "HH:mm") LocalTime start, @JsonFormat(pattern = "HH:mm") LocalTime end,
            Phase phase, String status, Instant checkIn, Instant checkOut, int workedMinutes, int lateMinutes, int earlyMinutes,
            LateReason lateReason, EarlyReason earlyReason, Boolean lateExcused, Boolean earlyExcused, UUID recordId, Long version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LateReason(String text, String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EarlyReason(String code, String note, String status) {
    }

    record Gate(UUID id, String name) {
    }

    /** The last scan today was refused as outside the school area: how far, the area. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Outside(Double distanceKm, Double radiusKm, String areaName, Instant at) {
    }

    /** The last working day before today (at most a week back) was checked in but never out. */
    record MissingCheckOut(LocalDate date, int shiftIdx, String shiftName, Instant checkIn, UUID recordId) {
    }

    /**
     * This month so far: the four tiles (present = on time + out early; absent = absent + missing check-out) and "6 of
     * 22 working days" ({@code elapsedDays} the days counted in the tiles, {@code workingDays} the month's days with
     * shifts, leave days included).
     */
    record Month(@JsonFormat(pattern = "yyyy-MM") YearMonth month, int present, int late, int absent, int leave, int workingDays,
            int elapsedDays) {
    }
}
