package kh.cmt.schedule.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A shift template as the Admin Web shows it (template card and drawer). Each shift has its name (or "Shift 1" /
 * "Shift 2" on the page when it has none), times and length in minutes; {@code totalMinutes} is the working time of the
 * day; {@code usedBy}: active people whose week today uses it ("Used by N people"); {@code checkInOpensMinutes}: how long
 * before a shift starts a check-in is taken (left out = no limit). For inactive templates, who retired it, when and why.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShiftTemplateView(UUID id, String name, String color, List<Shift> shifts, long totalMinutes, int graceMinutes,
        boolean earlyCheckOutNeedsReason, Integer checkInOpensMinutes, long usedBy, boolean inactive, Instant deletedAt, UUID deletedBy, String deleteReason,
        long version, Instant createdAt, Instant updatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shift(int position, String name, @JsonFormat(pattern = "HH:mm") LocalTime start,
            @JsonFormat(pattern = "HH:mm") LocalTime end, long minutes) {
    }
}
