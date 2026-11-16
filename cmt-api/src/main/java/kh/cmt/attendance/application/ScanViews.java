package kh.cmt.attendance.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.web.ErrorCode;

/** What a scan answers. */
public final class ScanViews {

    private ScanViews() {
    }

    /**
     * A check-in or check-out as recorded. {@code result} CHECKED_IN | CHECKED_OUT; {@code status} ON_TIME | LATE |
     * EARLY; minutes for this shift; {@code workedTodayMinutes} all shifts of the day; {@code nextShift} the shift still
     * to come today (after a check-out); times {@code HH:mm} in school time.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String result, Instant time, int shiftIdx, String shiftName, boolean twoShifts, String status, int lateMinutes,
            int earlyMinutes, int workedMinutes, int workedTodayMinutes, @JsonFormat(pattern = "HH:mm") LocalTime shiftStart,
            @JsonFormat(pattern = "HH:mm") LocalTime shiftEnd, NextShift nextShift, Gate gate, UUID recordId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NextShift(int shiftIdx, String name, @JsonFormat(pattern = "HH:mm") LocalTime start) {
    }

    public record Gate(UUID id, String name) {
    }

    /** What the scan service decided: done, or refused with a code and its details (sent as problem JSON). */
    public sealed interface Outcome permits Done, Refused {
    }

    public record Done(Result result) implements Outcome {
    }

    public record Refused(ErrorCode code, Map<String, Object> details) implements Outcome {
    }
}
