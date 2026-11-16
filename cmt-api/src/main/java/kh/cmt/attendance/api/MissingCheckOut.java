package kh.cmt.attendance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outbox event {@value #TYPE}: the end-of-day job (step 7.8) found a check-in without a check-out and the school's
 * rule is "ask for a correction" — the person is told ("You forgot to check out"). {@code times}: the shifts concerned,
 * "07:45 – 17:00".
 */
public record MissingCheckOut(UUID personId, LocalDate date, List<String> times) {

    public static final String TYPE = "attendance.check-out-missing";
}
