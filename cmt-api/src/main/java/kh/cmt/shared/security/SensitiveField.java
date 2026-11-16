package kh.cmt.shared.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * Groups of personal data a role may or may not see (Roles page, "Sensitive fields"). When a role does not have a
 * group, those values show as {@link FieldMask#MASK} and exports leave the columns out.
 */
public enum SensitiveField {

    /** Salary, pay rates and bank details. */
    SALARY_BANK("salaryBank"),

    /** National ID number and personal documents. */
    NATIONAL_ID("nationalId"),

    /** Phone numbers. */
    PHONE("phone"),

    /** Scans of the national ID card. */
    ID_SCANS("idScans");

    private final String key;

    SensitiveField(String key) {
        this.key = key;
    }

    /** The name used by the apps. */
    public String key() {
        return key;
    }

    public static Optional<SensitiveField> fromKey(String key) {
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }
}
