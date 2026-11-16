package kh.cmt.payroll.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import kh.cmt.payroll.domain.PayRate;
import kh.cmt.payroll.domain.PayRate.NamedAmount;
import kh.cmt.payroll.infrastructure.PayRateRepository;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.people.PaySummaries;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's pay and its history (Add / Edit person › Pay; profile; People list chip).
 * <p>
 * Every change is a new row with the date it applies from; rows are never changed. The pay on a day is the newest row
 * that has started by then. A new row cannot go before the newest one; on the same date it replaces that row only
 * while it has not started yet (a planned change can still be corrected), otherwise a later date must be chosen.
 * <p>
 * Who: People view (history) or edit (new pay), the person in the caller's record scope, and the role's "See salary
 * &amp; bank details" — without it 403 ({@code field: salaryBank}).
 */
@Service
@Transactional
public class PayService implements PaySummaries {

    static final Set<String> TYPES = Set.of("monthly", "hourly", "none");
    static final Set<String> CURRENCIES = Set.of("USD", "KHR");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9A-Za-z -]{4,30}");

    private final PayRateRepository rates;
    private final PeopleQueries people;
    private final FieldMask fieldMask;
    private final Clock clock;

    PayService(PayRateRepository rates, PeopleQueries people, FieldMask fieldMask, Clock clock) {
        this.rates = rates;
        this.people = people;
        this.fieldMask = fieldMask;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public PayView.History history(UUID personId) {
        requireSalaryAccess();
        person(personId);
        return historyOf(rates.findByPersonIdOrderByEffectiveFromDesc(personId));
    }

    /** New pay from {@code effectiveFrom} on. Returns the whole history. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public PayView.History setPay(UUID personId, PayCommands.SetPay command) {
        requireSalaryAccess();
        PeopleQueries.VisiblePerson person = person(personId);
        if (!person.active()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        List<Map<String, String>> errors = new ArrayList<>();
        PayRate row = check(personId, command, errors);
        List<PayRate> existing = rates.findByPersonIdOrderByEffectiveFromDesc(personId);
        LocalDate today = LocalDate.now(clock);
        PayRate replaced = null;
        if (command.effectiveFrom() != null && !existing.isEmpty()) {
            PayRate newest = existing.get(0);
            if (command.effectiveFrom().isBefore(newest.getEffectiveFrom())) {
                errors.add(error("effectiveFrom", "BeforeLatest"));      // history only grows forward
            } else if (command.effectiveFrom().equals(newest.getEffectiveFrom())) {
                if (newest.getEffectiveFrom().isAfter(today)) {
                    replaced = newest;                                    // a planned change, not started: corrected
                } else {
                    errors.add(error("effectiveFrom", "SameDate"));      // in force: never changed, pick a later date
                }
            }
        }
        failIfAny(errors);
        if (replaced != null) {
            rates.delete(replaced);
            rates.flush();
        }
        rates.saveAndFlush(row);
        return historyOf(rates.findByPersonIdOrderByEffectiveFromDesc(personId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PaySummary> current(Collection<UUID> personIds) {
        Map<UUID, PaySummary> found = new HashMap<>();
        if (personIds.isEmpty()) {
            return found;
        }
        for (PayRate r : rates.startedBy(personIds, LocalDate.now(clock))) {
            found.putIfAbsent(r.getPersonId(), new PaySummary(r.getPayType(), r.getAmount(), r.getCurrency()));   // newest first
        }
        return found;
    }

    // ------------------------------------------------------------------ checks

    private PayRate check(UUID personId, PayCommands.SetPay c, List<Map<String, String>> errors) {
        String type = c.type() == null ? null : c.type().trim().toLowerCase(Locale.ROOT);
        if (type == null || type.isEmpty()) {
            errors.add(error("type", "NotNull"));
            type = "none";
        } else if (!TYPES.contains(type)) {
            errors.add(error("type", "OneOf"));
            type = "none";
        }
        boolean none = type.equals("none");
        String currency = none ? null : c.currency() == null || c.currency().isBlank() ? "USD" : c.currency().trim().toUpperCase(Locale.ROOT);
        if (currency != null && !CURRENCIES.contains(currency)) {
            errors.add(error("currency", "OneOf"));
        }
        BigDecimal amount = null;
        if (none) {
            if (c.amount() != null) {
                errors.add(error("amount", "NotForType"));
            }
        } else if (c.amount() == null) {
            errors.add(error("amount", "NotNull"));
        } else {
            amount = money(c.amount(), currency, "amount", errors);
        }

        Integer paymentDay = null;
        List<NamedAmount> allowances = named(c.allowances(), currency, "allowances", 10, errors);
        if (type.equals("monthly")) {
            paymentDay = c.paymentDay() == null ? 28 : c.paymentDay();
            if (paymentDay < 1 || paymentDay > 31) {
                errors.add(error("paymentDay", "Range"));
            }
        } else {
            onlyFor(c.paymentDay() != null, "paymentDay", errors);
            onlyFor(!allowances.isEmpty(), "allowances", errors);
        }

        BigDecimal multiplier = null;
        BigDecimal overtimeRate = null;
        if (type.equals("hourly")) {
            String overtime = c.overtime() == null || c.overtime().isBlank() ? "1.5" : c.overtime().trim().toLowerCase(Locale.ROOT);
            switch (overtime) {
                case "1.5", "2" -> {
                    multiplier = new BigDecimal(overtime);
                    if (c.overtimeRate() != null) {
                        errors.add(error("overtimeRate", "NotForType"));
                    }
                }
                case "custom" -> {
                    if (c.overtimeRate() == null) {
                        errors.add(error("overtimeRate", "NotNull"));
                    } else {
                        overtimeRate = money(c.overtimeRate(), currency, "overtimeRate", errors);
                    }
                }
                default -> errors.add(error("overtime", "OneOf"));
            }
        } else {
            onlyFor(c.overtime() != null && !c.overtime().isBlank(), "overtime", errors);
            onlyFor(c.overtimeRate() != null, "overtimeRate", errors);
        }

        if (c.effectiveFrom() == null) {
            errors.add(error("effectiveFrom", "NotNull"));
        }
        String bank = text(c.bankName(), 100, "bankName", errors);
        String account = text(c.accountNumber(), 30, "accountNumber", errors);
        if (account != null && !ACCOUNT.matcher(account).matches()) {
            errors.add(error("accountNumber", "Pattern"));
        }
        String accountName = text(c.accountName(), 150, "accountName", errors);
        String tax = text(c.taxNumber(), 30, "taxNumber", errors);
        return PayRate.of(personId, type, amount, currency, paymentDay, allowances, multiplier, overtimeRate,
                c.effectiveFrom(), bank, account, accountName, tax);
    }

    /** More than 0, at most a billion; dollars with at most 2 decimals, riel in whole numbers. */
    private static BigDecimal money(BigDecimal value, String currency, String field, List<Map<String, String>> errors) {
        if (value.signum() <= 0 || value.compareTo(MAX_AMOUNT) > 0) {
            errors.add(error(field, "Range"));
        } else if ("KHR".equals(currency) && value.stripTrailingZeros().scale() > 0) {
            errors.add(error(field, "WholeRiel"));
        } else if (value.stripTrailingZeros().scale() > 2) {
            errors.add(error(field, "Scale"));
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static List<NamedAmount> named(List<PayCommands.Named> in, String currency, String field, int max,
            List<Map<String, String>> errors) {
        List<NamedAmount> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        if (in.size() > max) {
            errors.add(error(field, "Size"));
        }
        for (PayCommands.Named n : in) {
            if (n == null) {
                continue;
            }
            String name = n.name() == null ? "" : n.name().trim().replaceAll("\\s+", " ");
            if (name.isEmpty() || name.length() > 50) {
                errors.add(error(field + ".name", name.isEmpty() ? "NotBlank" : "Size"));
            }
            if (n.amount() == null) {
                errors.add(error(field + ".amount", "NotNull"));
                continue;
            }
            out.add(new NamedAmount(name, money(n.amount(), currency, field + ".amount", errors)));
        }
        return out;
    }

    private static void onlyFor(boolean given, String field, List<Map<String, String>> errors) {
        if (given) {
            errors.add(error(field, "NotForType"));
        }
    }

    private static String text(String value, int max, String field, List<Map<String, String>> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > max) {
            errors.add(error(field, "Size"));
        }
        return cleaned;
    }

    // ------------------------------------------------------------------ history

    private PayView.History historyOf(List<PayRate> newestFirst) {
        LocalDate today = LocalDate.now(clock);
        List<PayView> all = new ArrayList<>();
        PayView current = null;
        List<PayView> upcoming = new ArrayList<>();
        for (int i = 0; i < newestFirst.size(); i++) {
            PayRate r = newestFirst.get(i);
            PayRate before = i + 1 < newestFirst.size() ? newestFirst.get(i + 1) : null;
            String status;
            if (r.getEffectiveFrom().isAfter(today)) {
                status = "UPCOMING";
            } else if (current == null) {
                status = "CURRENT";
            } else {
                status = "PAST";
            }
            PayView view = view(r, before, status);
            all.add(view);
            if (status.equals("UPCOMING")) {
                upcoming.add(0, view);   // soonest first
            } else if (status.equals("CURRENT")) {
                current = view;
            }
        }
        return new PayView.History(current, upcoming, all);
    }

    private static PayView view(PayRate r, PayRate before, String status) {
        return new PayView(r.getId(), r.getPayType(), r.getAmount(), r.getCurrency(), r.getPaymentDay(), r.getAllowances(),
                r.getOvertimeMultiplier(), r.getOvertimeRate(), r.getEffectiveFrom(),
                r.getBankName(), r.getAccountNumber(), r.getAccountName(), r.getTaxNumber(), status, changes(before, r),
                r.getCreatedBy(), r.getCreatedAt());
    }

    /** What differs from the row before (none for the first row). */
    static List<PayView.Change> changes(PayRate before, PayRate after) {
        List<PayView.Change> changes = new ArrayList<>();
        if (before == null) {
            return changes;
        }
        compare(changes, "type", before, after, PayRate::getPayType);
        compare(changes, "amount", before, after, r -> money(r.getAmount(), r.getCurrency()));
        compare(changes, "paymentDay", before, after, r -> r.getPaymentDay() == null ? null : String.valueOf(r.getPaymentDay()));
        compare(changes, "allowances", before, after, r -> listText(r.getAllowances(), r.getCurrency()));
        compare(changes, "overtime", before, after, r -> r.getOvertimeMultiplier() != null ? "x" + r.getOvertimeMultiplier().stripTrailingZeros().toPlainString()
                : money(r.getOvertimeRate(), r.getCurrency()));
        compare(changes, "bankName", before, after, PayRate::getBankName);
        compare(changes, "accountNumber", before, after, PayRate::getAccountNumber);
        compare(changes, "accountName", before, after, PayRate::getAccountName);
        compare(changes, "taxNumber", before, after, PayRate::getTaxNumber);
        return changes;
    }

    private static void compare(List<PayView.Change> changes, String field, PayRate before, PayRate after, Function<PayRate, String> text) {
        String a = text.apply(before);
        String b = text.apply(after);
        if (!Objects.equals(a, b)) {
            changes.add(new PayView.Change(field, a, b));
        }
    }

    /** "$450.00", "៛1,800,000" — the page formats again; this is for the change list. */
    private static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        return ("KHR".equals(currency) ? "៛" : "$") + String.format(Locale.ROOT, "%,.2f", amount).replaceAll("\\.00$", "");
    }

    private static String listText(List<NamedAmount> items, String currency) {
        if (items.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        items.forEach(n -> parts.add(n.name() + " " + money(n.amount(), currency)));
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ helpers

    private void requireSalaryAccess() {
        if (!fieldMask.allows(SensitiveField.SALARY_BANK)) {
            throw new ApiException(ErrorCode.FORBIDDEN).with("field", SensitiveField.SALARY_BANK.key());
        }
    }

    private PeopleQueries.VisiblePerson person(UUID personId) {
        return people.visible(personId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static void failIfAny(List<Map<String, String>> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
    }
}
