package kh.cmt.schedule.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** What the Admin Web sends for date changes and holidays. */
public final class DateChangeCommands {

    private DateChangeCommands() {
    }

    /**
     * Date changes: the picked {@code dates} (today or later), for {@code target} everyone (default) | department |
     * person with {@code targetId}, the shift template ({@code templateId}; leave out for Off) and an optional
     * {@code label} such as "Exam day". A date that already has a change for the same target gets the new one.
     */
    public record Create(List<LocalDate> dates, String target, UUID targetId, UUID templateId, String label) {
    }

    /**
     * A holiday: {@code name} (Khmer or English required), {@code kind} NATIONAL | SCHOOL, from {@code startDate} to
     * {@code endDate} (leave out for one day), for everyone or {@code departmentId}. {@code version} when changing.
     */
    public record SaveHoliday(LocalizedName name, String kind, LocalDate startDate, LocalDate endDate, UUID departmentId,
            Long version) {
    }
}
