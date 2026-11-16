package kh.cmt.org.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import kh.cmt.org.application.OrgItemCommand.OrgName;

/**
 * An item as the apps see it: its {@code name}, {@code color} where the kind has one. For inactive items, {@code deletedAt}, {@code deletedBy} (an
 * account id) and {@code deleteReason} say who retired it and when. {@code people}: for departments and positions,
 * the number of active people who have it ("people using it").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgItemView(UUID id, OrgName name, String color, boolean inactive, Instant deletedAt,
        UUID deletedBy, String deleteReason, long version, Instant createdAt, Instant updatedAt, Long people) {

    OrgItemView withPeople(Long count) {
        return new OrgItemView(id, name, color, inactive, deletedAt, deletedBy, deleteReason, version, createdAt,
                updatedAt, count);
    }
}
