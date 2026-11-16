package kh.cmt.attendance.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import kh.cmt.attendance.api.TodayCard.Phase;
import kh.cmt.attendance.api.TodayCard.State;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.schedule.api.DaySchedules;

/**
 * Today's card (step 7.9) from today's schedule, its shifts ({@link DayHistory#today}) and the clock. No database:
 * every state is unit-tested ({@code TodayStatesTest}); the order is the one of {@link State}.
 */
final class TodayStates {

    private TodayStates() {
    }

    /** {@code current}: index into {@code phases} of the shift the card shows first. */
    record Card(State state, int current, List<Phase> phases) {
    }

    /**
     * @param outsideLast the last scan today was refused as outside the area
     * @param missingBefore the last working day was never checked out (and no correction was asked)
     */
    static Card of(DaySchedules.ScheduledDay day, List<DayHistory.Line> lines, Instant now, ZoneId zone, boolean outsideLast,
            boolean missingBefore) {
        List<Phase> phases = phases(day, lines, now, zone);
        int current = current(phases);
        return new Card(state(day, lines, phases, current, outsideLast, missingBefore), current, phases);
    }

    private static State state(DaySchedules.ScheduledDay day, List<DayHistory.Line> lines, List<Phase> phases, int current,
            boolean outsideLast, boolean missingBefore) {
        switch (day.kind()) {
            case HOLIDAY -> {
                return State.HOLIDAY;
            }
            case LEAVE -> {
                return State.ON_LEAVE;
            }
            case OFF, NO_SCHEDULE -> {
                return State.DAY_OFF;
            }
            case WORK -> {
                // below
            }
        }
        List<Phase> active = phases.stream().filter(p -> p != Phase.LEAVE).toList();
        if (active.isEmpty()) {
            return State.ON_LEAVE;
        }
        boolean attended = lines.stream().anyMatch(l -> l.record() != null && l.record().getInAt() != null);
        if (!attended) {
            if (active.stream().allMatch(is(Phase.MISSED))) {
                return State.ABSENT;
            }
            return outsideLast ? State.OUTSIDE_AREA : missingBefore ? State.MISSING_CHECK_OUT : State.NOT_CHECKED_IN;
        }
        Phase shown = phases.get(current);
        if (shown == Phase.WORKING) {
            return State.WORKING;
        }
        if (shown == Phase.UPCOMING) {
            if (outsideLast) {
                return State.OUTSIDE_AREA;
            }
            boolean before = current > 0 && finished(phases.get(current - 1));
            return before ? State.BREAK : State.NOT_CHECKED_IN;
        }
        if (active.stream().allMatch(TodayStates::finished)) {
            return active.size() == 1 && active.getFirst() == Phase.EARLY ? State.EARLY_OUT : State.DONE;
        }
        return State.MISSED;
    }

    /** One phase per line: on leave, by its record, else by the clock. */
    private static List<Phase> phases(DaySchedules.ScheduledDay day, List<DayHistory.Line> lines, Instant now, ZoneId zone) {
        List<Phase> phases = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            DayHistory.Line l = lines.get(i);
            AttendanceRecord r = l.record();
            boolean over = !now.isBefore(day.date().atTime(l.end()).atZone(zone).toInstant());
            if (l.status() == DayHistory.ShiftStatus.LEAVE) {
                phases.add(Phase.LEAVE);
            } else if (r == null || r.getInAt() == null) {
                phases.add(r != null && r.getStatus() == AttendanceRecord.Status.ABSENT || over ? Phase.MISSED : Phase.UPCOMING);
            } else if (r.getOutAt() != null) {
                phases.add(r.getStatus() == AttendanceRecord.Status.EARLY ? Phase.EARLY : Phase.DONE);
            } else {
                boolean movedOn = lines.subList(i + 1, lines.size()).stream().anyMatch(n -> n.record() != null && n.record().getInAt() != null);
                phases.add(movedOn || r.getStatus() == AttendanceRecord.Status.MISSING ? Phase.MISSED : Phase.WORKING);
            }
        }
        return phases;
    }

    /** The running shift, else the next one to come, else the last one that is not on leave. */
    private static int current(List<Phase> phases) {
        int working = phases.indexOf(Phase.WORKING);
        if (working >= 0) {
            return working;
        }
        int next = phases.indexOf(Phase.UPCOMING);
        if (next >= 0) {
            return next;
        }
        for (int i = phases.size() - 1; i >= 0; i--) {
            if (phases.get(i) != Phase.LEAVE) {
                return i;
            }
        }
        return 0;
    }

    private static boolean finished(Phase p) {
        return p == Phase.DONE || p == Phase.EARLY;
    }

    private static Predicate<Phase> is(Phase phase) {
        return p -> p == phase;
    }
}
