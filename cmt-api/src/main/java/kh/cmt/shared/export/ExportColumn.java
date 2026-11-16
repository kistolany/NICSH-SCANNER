package kh.cmt.shared.export;

import java.util.function.Function;
import kh.cmt.shared.security.SensitiveField;

/**
 * One column of an export: its header, how to get the value from a row, and (for sensitive data) which group it
 * belongs to. A sensitive column is left out completely when the caller's role may not see that group.
 *
 * @param header    column title, already in the caller's language
 * @param value     the cell value of a row (text, number, date, boolean or null)
 * @param sensitive the group of sensitive data, or null for an ordinary column
 */
public record ExportColumn<T>(String header, Function<T, Object> value, SensitiveField sensitive) {

    public static <T> ExportColumn<T> of(String header, Function<T, Object> value) {
        return new ExportColumn<>(header, value, null);
    }

    public static <T> ExportColumn<T> sensitive(String header, SensitiveField field, Function<T, Object> value) {
        return new ExportColumn<>(header, value, field);
    }
}
