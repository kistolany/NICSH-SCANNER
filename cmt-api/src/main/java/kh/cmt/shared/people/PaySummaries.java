package kh.cmt.shared.people;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The pay in force today, for the People list and the profile (pay type chip, "$450 / month"). Provided by the payroll
 * module, which itself uses the people module. Shown only to roles with "See salary &amp; bank details".
 */
public interface PaySummaries {

    /** {@code type} monthly | hourly | none; amount and currency (null for none). */
    record PaySummary(String type, BigDecimal amount, String currency) {
    }

    /** The pay in force today of these people (people without pay are left out). */
    Map<UUID, PaySummary> current(Collection<UUID> personIds);
}
