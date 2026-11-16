package kh.cmt.people.application;

import java.util.Optional;
import java.util.UUID;
import kh.cmt.files.api.FileReadPolicy;
import kh.cmt.people.domain.PersonDocument;
import kh.cmt.people.infrastructure.PersonDirectory;
import kh.cmt.people.infrastructure.PersonDirectory.FileOwner;
import kh.cmt.people.infrastructure.PersonRepository;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionGuard;
import kh.cmt.shared.security.PermissionModule;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may open a person's photo or documents: the person themselves, and callers with People view who have the
 * person in their record scope — for documents also the sensitive-field group of the document (national ID scans,
 * or national ID &amp; documents). Any other file: not ours.
 */
@Component
class PeopleFilePolicy implements FileReadPolicy {

    private final PersonDirectory directory;
    private final PersonRepository people;
    private final PermissionGuard permissions;
    private final FieldMask fieldMask;

    PeopleFilePolicy(PersonDirectory directory, PersonRepository people, PermissionGuard permissions, FieldMask fieldMask) {
        this.directory = directory;
        this.people = people;
        this.permissions = permissions;
        this.fieldMask = fieldMask;
    }

    @Override
    @Transactional(readOnly = true)
    public Decision decide(UUID fileId, CurrentUser caller) {
        Optional<FileOwner> owner = directory.ownerOfFile(fileId);
        if (owner.isEmpty()) {
            return Decision.ABSTAIN;
        }
        UUID person = owner.get().personId();
        if (person.equals(caller.personId())) {
            return Decision.ALLOW;   // their own photo / document
        }
        if (!permissions.allows(caller, PermissionModule.PEOPLE, PermissionAction.VIEW)) {
            return Decision.DENY;
        }
        if (people.findById(person).filter(p -> p.getSchoolId().equals(caller.schoolId())).isEmpty()) {
            return Decision.DENY;    // outside the caller's record scope
        }
        String type = owner.get().documentType();
        if (type != null && !fieldMask.allows(PersonDocument.Type.valueOf(type).group())) {
            return Decision.DENY;
        }
        return Decision.ALLOW;
    }
}
