package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Settings, Notifications: when reminders are sent. Check-in and shift-2 reminders go
 * {@code ...Min} minutes before the start; the check-out reminder goes {@code checkOutReminderMin} minutes after
 * the shift ends (0 = at the end). Each reminder can be switched off.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationSettings(
        @NotNull Boolean checkInReminder, @NotNull @Min(1) @Max(120) Integer checkInReminderMin,
        @NotNull Boolean checkOutReminder, @NotNull @Min(0) @Max(120) Integer checkOutReminderMin,
        @NotNull Boolean shift2Reminder, @NotNull @Min(1) @Max(120) Integer shift2ReminderMin, Long version) {
}
