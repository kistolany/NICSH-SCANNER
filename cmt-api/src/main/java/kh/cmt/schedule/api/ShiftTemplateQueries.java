package kh.cmt.schedule.api;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules (and the schedule's own resolver) may ask about shift templates: the shifts of a template with
 * their times, grace and the early check-out rule.
 */
public interface ShiftTemplateQueries {

    /** A template of the caller's school, active or not (old schedules may still point at a retired one). */
    Optional<TemplateInfo> find(UUID id);

    /** {@code checkInOpensMinutes}: minutes before a shift starts from which a scan checks in; null = no limit. */
    record TemplateInfo(UUID id, String name, String color, int graceMinutes, boolean earlyCheckOutNeedsReason, boolean active,
            List<Shift> shifts, Integer checkInOpensMinutes) {

        public TemplateInfo(UUID id, String name, String color, int graceMinutes, boolean earlyCheckOutNeedsReason, boolean active,
                List<Shift> shifts) {
            this(id, name, color, graceMinutes, earlyCheckOutNeedsReason, active, shifts, null);
        }

        public record Shift(int position, String name, LocalTime start, LocalTime end) {
        }
    }
}
