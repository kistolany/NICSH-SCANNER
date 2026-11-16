package kh.cmt.payroll.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of a person's pay history: what they are paid from {@code effectiveFrom} on. Rows are never changed (the
 * database refuses it); a change of pay is a new row. Limited to the caller's record scope like the person.
 */
@Entity
@Table(name = "pay_rate")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
public class PayRate extends BaseEntity {

    /** A named amount: an allowance ("Transport", 30) or a rate per subject / grade ("Grade 12 Math", 9). */
    public record NamedAmount(String name, BigDecimal amount) {
    }

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "pay_type", updatable = false)
    private String payType;

    @Column(updatable = false)
    private BigDecimal amount;

    @Column(updatable = false)
    private String currency;

    @Column(name = "payment_day", updatable = false)
    private Integer paymentDay;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<NamedAmount> allowances = new ArrayList<>();

    @Column(name = "overtime_multiplier", updatable = false)
    private BigDecimal overtimeMultiplier;

    @Column(name = "overtime_rate", updatable = false)
    private BigDecimal overtimeRate;

    @Column(name = "effective_from", updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "bank_name", updatable = false)
    private String bankName;

    @Column(name = "account_number", updatable = false)
    private String accountNumber;

    @Column(name = "account_name", updatable = false)
    private String accountName;

    @Column(name = "tax_number", updatable = false)
    private String taxNumber;

    /** Everything at once: a row is made complete and never changed afterwards. */
    public static PayRate of(UUID personId, String payType, BigDecimal amount, String currency, Integer paymentDay,
            List<NamedAmount> allowances, BigDecimal overtimeMultiplier, BigDecimal overtimeRate,
            LocalDate effectiveFrom, String bankName, String accountNumber, String accountName,
            String taxNumber) {
        PayRate r = new PayRate();
        r.personId = personId;
        r.payType = payType;
        r.amount = amount;
        r.currency = currency;
        r.paymentDay = paymentDay;
        r.allowances = new ArrayList<>(allowances);
        r.overtimeMultiplier = overtimeMultiplier;
        r.overtimeRate = overtimeRate;
        r.effectiveFrom = effectiveFrom;
        r.bankName = bankName;
        r.accountNumber = accountNumber;
        r.accountName = accountName;
        r.taxNumber = taxNumber;
        return r;
    }

    public UUID getPersonId() {
        return personId;
    }

    public String getPayType() {
        return payType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getPaymentDay() {
        return paymentDay;
    }

    public List<NamedAmount> getAllowances() {
        return allowances == null ? List.of() : allowances;
    }

    public BigDecimal getOvertimeMultiplier() {
        return overtimeMultiplier;
    }

    public BigDecimal getOvertimeRate() {
        return overtimeRate;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getBankName() {
        return bankName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getTaxNumber() {
        return taxNumber;
    }
}
