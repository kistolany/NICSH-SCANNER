package kh.cmt.people.api;

import java.util.UUID;

/**
 * What other modules may ask the people module. Answers are for the whole school, whatever the caller's record
 * scope (e.g. linking a web user to a person the caller cannot see in lists).
 */
public interface PeopleQueries {

    /** True if the person exists in the caller's school and is not inactive. */
    boolean isActive(UUID personId);

    /**
     * The person as the caller may see them: empty if unknown, of another school or outside the caller's record scope
     * (the one question here that does follow the scope). {@code active} false for inactive people.
     */
    java.util.Optional<VisiblePerson> visible(UUID personId);

    record VisiblePerson(UUID id, boolean active, String role, UUID departmentId) {
    }

    /** Every active person of the caller's school with their department (whole school), e.g. to count who a schedule reaches. */
    java.util.List<PersonPlace> activePeople();

    /** A person's department, whole school (null if none / unknown). */
    UUID departmentOfPerson(UUID personId);

    record PersonPlace(UUID id, UUID departmentId) {
    }

    /**
     * The active people the caller may see (record scope applies), sorted by English name, e.g. the rows of the Devices
     * page.
     */
    java.util.List<PersonCard> visibleActive();

    record PersonCard(UUID id, String staffId, kh.cmt.shared.i18n.LocalizedName name, String role, UUID departmentId) {
    }

    /** Staff ID and name of these people (inactive ones too; unknown ids left out), e.g. "who" on a schedule list. */
    java.util.Map<UUID, PersonLabel> labels(java.util.Collection<UUID> ids);

    record PersonLabel(UUID id, String staffId, kh.cmt.shared.i18n.LocalizedName name, boolean inactive, UUID photoFileId) {
    }

    /** Employment start date of these people (whole school; people without one left out), e.g. for leave entitlements. */
    java.util.Map<UUID, java.time.LocalDate> startDates(java.util.Collection<UUID> ids);
}
