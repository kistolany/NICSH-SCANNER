package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Settings, Numbering: how Staff IDs look.
 * <ul>
 *   <li>{@code prefix}: 1-10 letters or digits, stored in capitals (CMT)</li>
 *   <li>{@code separator}: up to 3 of {@code - _ . /}, or empty</li>
 *   <li>{@code length}: digits of the number, 3-8 (0151 has 4); a bigger number simply gets longer</li>
 *   <li>{@code rolePrefix}: add STF / TCH / ADM after the prefix</li>
 *   <li>{@code includeYear}: add the two-digit year; the number then starts again at 1 each year</li>
 * </ul>
 * {@code nextNumber} is read-only (what the next new person will get); it is ignored when saving. Use the
 * advance call to move it forward. {@code version} is the revision of this section, as for the other sections.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NumberingSettings(@NotNull String prefix, @NotNull String separator, @NotNull @Min(3) @Max(8) Integer length,
        @NotNull Boolean rolePrefix, @NotNull Boolean includeYear, Long nextNumber, Long version) {
}
