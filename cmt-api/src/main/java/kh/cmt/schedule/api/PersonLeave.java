package kh.cmt.schedule.api;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Approved leave of a person on a day, for the schedule resolver. The leave module implements it (step 9.2). (Defined
 * here so that schedule does not depend on leave: leave depends on schedule for the days a request covers.)
 */
public interface PersonLeave {

    /**
     * {@code wholeDay}, or the shift positions (1, 2) the leave covers, or a half day: {@code half} AM | PM — on a
     * two-shift day shift 1 / shift 2, on a one-shift day the first / second half of the shift (the resolver moves the
     * shift's start / end to its middle).
     */
    record LeaveDay(UUID id, String type, boolean wholeDay, Set<Integer> shiftPositions, String half) {

        public LeaveDay(UUID id, String type, boolean wholeDay, Set<Integer> shiftPositions) {
            this(id, type, wholeDay, shiftPositions, null);
        }
    }

    Optional<LeaveDay> leaveOn(UUID personId, LocalDate day);

    /** {@link #leaveOn} for every day of {@code from} – {@code to} (days without leave left out). */
    default Map<LocalDate, LeaveDay> leavesIn(UUID personId, LocalDate from, LocalDate to) {
        Map<LocalDate, LeaveDay> out = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate day = d;
            leaveOn(personId, day).ifPresent(l -> out.put(day, l));
        }
        return out;
    }
}
