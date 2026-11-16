package kh.cmt.org.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What other modules may ask the org module (people...). Everything else in {@code kh.cmt.org} is
 * internal. Inactive items stay valid on old records but must not be picked for new ones.
 */
public interface OrgQueries {

    /** True if the item exists in the caller's school and is not inactive: it may be chosen on a new record. */
    boolean isActive(OrgKind kind, UUID id);

    /** Names / labels of these items of the caller's school, inactive ones too; unknown ids are left out. */
    Map<UUID, OrgLabel> labels(OrgKind kind, Collection<UUID> ids);

    /** The active item with this name (English or Khmer) or label, ignoring case; for imports. */
    java.util.Optional<UUID> activeByName(OrgKind kind, String name);

    /** English names / labels of the active items, sorted; for import templates. */
    java.util.List<String> activeNames(OrgKind kind);

    /** The active items of the caller's school, sorted as their list, e.g. a dropdown. */
    java.util.List<OrgLabel> active(OrgKind kind);
}
