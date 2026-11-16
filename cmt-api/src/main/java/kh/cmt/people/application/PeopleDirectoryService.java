package kh.cmt.people.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.people.infrastructure.PersonDirectory;
import kh.cmt.shared.people.PeopleCounts;
import kh.cmt.shared.security.PersonDepartments;
import kh.cmt.shared.security.SchoolContext;
import org.springframework.stereotype.Service;

/**
 * What the people module tells other modules: whether a person may be linked, a person's department (for the "own
 * department" scope, read in plain SQL while a scoped query is prepared), and counts of people.
 */
@Service
class PeopleDirectoryService implements PeopleQueries, PersonDepartments, PeopleCounts {

    private final PersonDirectory directory;
    private final kh.cmt.people.infrastructure.PersonRepository people;
    private final SchoolContext schoolContext;

    PeopleDirectoryService(PersonDirectory directory, kh.cmt.people.infrastructure.PersonRepository people,
            SchoolContext schoolContext) {
        this.directory = directory;
        this.people = people;
        this.schoolContext = schoolContext;
    }

    @Override
    public boolean isActive(UUID personId) {
        return personId != null && directory.isActive(schoolContext.currentSchoolId(), personId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<VisiblePerson> visible(UUID personId) {
        UUID school = schoolContext.currentSchoolId();
        return personId == null ? Optional.empty() : people.findById(personId)   // the record scope applies
                .filter(p -> p.getSchoolId().equals(school))
                .map(p -> new VisiblePerson(p.getId(), !p.isInactive(), p.getRole(), p.getDepartmentId()));
    }

    @Override
    public java.util.Map<UUID, PersonLabel> labels(java.util.Collection<UUID> ids) {
        java.util.Map<UUID, PersonLabel> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        directory.refs(ids).forEach((id, r) -> out.put(id, new PersonLabel(id, r.staffId(),
                new kh.cmt.shared.i18n.LocalizedName(r.nameKm(), r.nameEn(), r.nameAr()), r.inactive(), r.photoFileId())));
        return out;
    }

    @Override
    public Map<UUID, java.time.LocalDate> startDates(java.util.Collection<UUID> ids) {
        return ids == null || ids.isEmpty() ? Map.of() : directory.startDates(ids);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<PersonCard> visibleActive() {
        UUID school = schoolContext.currentSchoolId();
        return people.findBySchoolId(school).stream()   // the record scope and "active only" filters apply
                .map(p -> new PersonCard(p.getId(), p.getStaffId(),
                        new kh.cmt.shared.i18n.LocalizedName(p.getName().getKm(), p.getName().getEn(), p.getName().getAr()),
                        p.getRole(), p.getDepartmentId()))
                .sorted(java.util.Comparator.comparing((PersonCard c) -> c.name().en() == null ? "" : c.name().en(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public java.util.List<PersonPlace> activePeople() {
        return directory.activePeople(schoolContext.currentSchoolId());
    }

    @Override
    public UUID departmentOfPerson(UUID personId) {
        return personId == null ? null : directory.departmentOf(personId).orElse(null);
    }

    @Override
    public Optional<UUID> departmentOf(UUID personId) {
        return directory.departmentOf(personId);
    }

    @Override
    public Map<UUID, Long> perDepartment() {
        return directory.countsBy(schoolContext.currentSchoolId(), "department_id");
    }

    @Override
    public Map<UUID, Long> perPosition() {
        return directory.countsBy(schoolContext.currentSchoolId(), "position_id");
    }

    @Override
    public long active(Set<UUID> departments) {
        return directory.countActive(schoolContext.currentSchoolId(), departments);
    }
}
