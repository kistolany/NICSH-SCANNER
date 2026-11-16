package kh.cmt.schedule.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalTime;
import java.util.List;

/** What the Admin Web sends for shift templates. */
public final class ShiftTemplateCommands {

    private ShiftTemplateCommands() {
    }

    /**
     * A template: {@code name}, {@code shifts} (1 or 2, in order), {@code graceMinutes} (0–120; the school's default
     * grace when left out), {@code earlyCheckOutNeedsReason} (the school's rule when left out), {@code color}
     * (#RRGGBB; #EA580C when left out), {@code checkInOpensMinutes} (0–720: a check-in is taken from that many minutes
     * before each shift starts; left out = no limit). {@code version} when changing one.
     */
    public record Save(String name, List<Shift> shifts, Integer graceMinutes, Boolean earlyCheckOutNeedsReason, String color,
            Integer checkInOpensMinutes, Long version) {
    }

    /** A shift of the day: optional name ("Morning"), start and end as "HH:mm". */
    public record Shift(String name, @JsonFormat(pattern = "HH:mm") LocalTime start, @JsonFormat(pattern = "HH:mm") LocalTime end) {
    }
}
