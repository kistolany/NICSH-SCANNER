package kh.cmt.people.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * The answer to an import preview or commit.
 * <ul>
 *   <li>{@code imported}: false for the preview (nothing saved), true for the commit</li>
 *   <li>{@code rows}: every row of the file with its Excel row number, what it says (Staff ID, English name, role,
 *       department), {@code status} OK | ERROR and the {@code errors} [{column, field, rule}]; after the commit the
 *       saved people have their {@code personId} and handed-out {@code staffId}</li>
 *   <li>{@code good} rows are (or would be) imported, {@code bad} rows are skipped</li>
 * </ul>
 */
public record ImportResult(boolean imported, int total, int good, int bad, List<RowResult> rows) {

    public static final String OK = "OK";
    public static final String ERROR = "ERROR";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowResult(int row, String staffId, String name, String role, String department, String status,
            List<Problem> errors, UUID personId) {
    }

    /** {@code column}: the header in the file; {@code field} / {@code rule}: as in Add person (e.g. role / OneOf). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Problem(String column, String field, String rule) {
    }
}
