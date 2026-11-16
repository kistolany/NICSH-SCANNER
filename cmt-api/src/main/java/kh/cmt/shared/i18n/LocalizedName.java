package kh.cmt.shared.i18n;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

/** A name in the three languages as it travels in JSON: {@code {"km": "...", "en": "...", "ar": "..."}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalizedName(@Size(max = 100) String km, @Size(max = 100) String en, @Size(max = 100) String ar) {
}
