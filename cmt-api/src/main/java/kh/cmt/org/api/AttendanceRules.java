package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Settings, Attendance rules. {@code defaultGraceMinutes}: minutes after the start before a check-in counts as
 * late (a shift template can set its own). The app reads these before it shows the scan results.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttendanceRules(@NotNull @Min(0) @Max(120) Integer defaultGraceMinutes,
        @NotNull Boolean earlyCheckOutNeedsReason, @NotNull Boolean manualCheckInAllowed,
        @NotNull Boolean offlineCheckInAllowed, @NotNull MissingCheckOutRule missingCheckOutRule, Long version) {
}
