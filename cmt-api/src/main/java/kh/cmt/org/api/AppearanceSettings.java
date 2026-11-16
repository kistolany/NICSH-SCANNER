package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

/** Settings, Appearance: what new users start with (each user can still choose their own). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppearanceSettings(@NotNull Language defaultLanguage, @NotNull Theme defaultTheme, Long version) {
}
