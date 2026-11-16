package kh.cmt.payroll.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.payroll.domain.PayRate.NamedAmount;

/**
 * One row of the pay history.
 * <ul>
 *   <li>{@code status} CURRENT (in force today) | UPCOMING (starts later) | PAST</li>
 *   <li>{@code changes}: what differs from the row before, as text {field, before, after} — "Monthly salary · $420 →
 *       $450" on the page; empty for the first row</li>
 *   <li>{@code changedBy} (account id) and {@code changedAt}: who saved it and when</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayView(UUID id, String type, BigDecimal amount, String currency, Integer paymentDay, List<NamedAmount> allowances,
        BigDecimal overtimeMultiplier, BigDecimal overtimeRate,
        LocalDate effectiveFrom, String bankName, String accountNumber, String accountName, String taxNumber, String status,
        List<Change> changes, UUID changedBy, Instant changedAt) {

    public record Change(String field, String before, String after) {
    }

    /** The whole pay page: what applies today, what is planned, and every row newest first. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record History(PayView current, List<PayView> upcoming, List<PayView> history) {
    }
}
