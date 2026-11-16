package kh.cmt.payroll.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the Admin Web sends for pay (Add / Edit person › Pay, and the bank block). */
public final class PayCommands {

    private PayCommands() {
    }

    /**
     * New pay from {@code effectiveFrom} on (the whole pay, also what stays the same).
     * <ul>
     *   <li>{@code type} monthly | hourly | none</li>
     *   <li>{@code amount}: monthly salary or hourly rate (not for none)</li>
     *   <li>{@code currency} USD (when left out) | KHR</li>
     *   <li>monthly: {@code paymentDay} (28 when left out), {@code allowances} [{name, amount}]</li>
     *   <li>hourly: {@code overtime} "1.5" (when left out) | "2" | "custom" with {@code overtimeRate}</li>
     *   <li>bank: {@code bankName}, {@code accountNumber}, {@code accountName}; {@code taxNumber} (tax / NSSF)</li>
     * </ul>
     */
    public record SetPay(String type, BigDecimal amount, String currency, Integer paymentDay, List<Named> allowances,
            String overtime, BigDecimal overtimeRate, LocalDate effectiveFrom, String bankName, String accountNumber, String accountName, String taxNumber) {
    }

    public record Named(String name, BigDecimal amount) {
    }
}
