package kh.cmt.org.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * What the apps send to create or change an item.
 * Departments, positions: {@code name} (English required) and, for departments, {@code color} such as
 * {@code #2563EB}.
 * {@code version} is required when changing an item: it is the version the user was looking at, so a change
 * made by somebody else in the meantime is reported ({@code VERSION_CONFLICT}) instead of overwritten.
 */
public record OrgItemCommand(@Valid OrgName name, @Size(max = 7) String color, Long version) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrgName(@Size(max = 100) String km, @Size(max = 100) String en, @Size(max = 100) String ar) {
    }
}
