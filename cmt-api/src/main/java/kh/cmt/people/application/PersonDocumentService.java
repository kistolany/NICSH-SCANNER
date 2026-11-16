package kh.cmt.people.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.files.api.FileRef;
import kh.cmt.files.api.Files;
import kh.cmt.people.domain.Person;
import kh.cmt.people.domain.PersonDocument;
import kh.cmt.people.infrastructure.PersonDocumentRepository;
import kh.cmt.people.infrastructure.PersonRepository;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's documents (profile › Documents): national ID front / back, contract, degrees, CV, health certificate,
 * police clearance, other — several of each kind may be kept. The file is uploaded first (files module, purpose
 * DOCUMENT), then added here with its issue and expiry dates. Expired documents are flagged.
 * <p>
 * Who sees what: People view and the person in the caller's record scope; national ID scans need "See national ID
 * scans", every other document "See national ID &amp; documents" — otherwise the document is listed as locked (type and
 * expiry only) and its file cannot be opened. Adding or removing needs People edit and the same group.
 */
@Service
@Transactional
public class PersonDocumentService {

    static final int EXPIRING_DAYS = 30;

    private final PersonRepository people;
    private final PersonDocumentRepository documents;
    private final Files files;
    private final FieldMask fieldMask;
    private final SchoolContext schoolContext;
    private final Clock clock;

    PersonDocumentService(PersonRepository people, PersonDocumentRepository documents, Files files, FieldMask fieldMask,
            SchoolContext schoolContext, Clock clock) {
        this.people = people;
        this.documents = documents;
        this.files = files;
        this.fieldMask = fieldMask;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public List<DocumentView> list(UUID personId) {
        return viewsOf(person(personId).getId());
    }

    /** {@code fileId}: an uploaded, confirmed file (purpose DOCUMENT) no other record uses. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public DocumentView add(UUID personId, PersonCommands.AddDocument command) {
        Person person = person(personId);
        if (person.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        List<Map<String, String>> errors = new ArrayList<>();
        PersonDocument.Type type = null;
        try {
            type = PersonDocument.Type.valueOf(command.type() == null ? "" : command.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.add(Map.of("field", "type", "rule", "OneOf"));
        }
        if (command.fileId() == null) {
            errors.add(Map.of("field", "fileId", "rule", "NotNull"));
        }
        if (command.issueDate() != null && command.expiryDate() != null && command.expiryDate().isBefore(command.issueDate())) {
            errors.add(Map.of("field", "expiryDate", "rule", "BeforeIssue"));
        }
        if (command.issueDate() != null && command.issueDate().isAfter(LocalDate.now(clock))) {
            errors.add(Map.of("field", "issueDate", "rule", "Future"));
        }
        String note = command.note() == null || command.note().isBlank() ? null : command.note().trim();
        if (note != null && note.length() > 200) {
            errors.add(Map.of("field", "note", "rule", "Size"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
        if (!fieldMask.allows(type.group())) {
            throw new ApiException(ErrorCode.FORBIDDEN).with("field", type.group().key());   // cannot add what you may not see
        }
        FileRef file = files.attach(command.fileId(), FilePurpose.DOCUMENT);
        PersonDocument document = new PersonDocument();
        document.setPersonId(person.getId());
        document.setType(type);
        document.setFileId(file.id());
        document.setIssueDate(command.issueDate());
        document.setExpiryDate(command.expiryDate());
        document.setNote(note);
        document = documents.saveAndFlush(document);
        return view(document, file);
    }

    /** Removes the document and its file. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public void delete(UUID personId, UUID documentId) {
        Person person = person(personId);
        PersonDocument document = documents.findById(documentId).filter(d -> d.getPersonId().equals(person.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!fieldMask.allows(document.getType().group())) {
            throw new ApiException(ErrorCode.FORBIDDEN).with("field", document.getType().group().key());
        }
        documents.delete(document);
        documents.flush();
        files.discard(document.getFileId());
    }

    /** The documents of a person the caller may see (no permission check: for the profile). */
    @Transactional(readOnly = true)
    List<DocumentView> viewsOf(UUID personId) {
        List<PersonDocument> found = documents.findByPersonIdOrderByTypeAscCreatedAtDesc(personId);
        Map<UUID, FileRef> refs = files.find(found.stream().map(PersonDocument::getFileId).toList());
        return found.stream().map(d -> view(d, refs.get(d.getFileId()))).toList();
    }

    private DocumentView view(PersonDocument d, FileRef file) {
        LocalDate today = LocalDate.now(clock);
        boolean expired = d.getExpiryDate() != null && d.getExpiryDate().isBefore(today);
        Integer daysLeft = d.getExpiryDate() == null || expired ? null : (int) ChronoUnit.DAYS.between(today, d.getExpiryDate());
        String status = expired ? "EXPIRED" : daysLeft != null && daysLeft <= EXPIRING_DAYS ? "EXPIRING" : "VALID";
        boolean locked = !fieldMask.allows(d.getType().group());
        return new DocumentView(d.getId(), d.getType().name(), d.getIssueDate(), d.getExpiryDate(), status, expired, daysLeft,
                locked ? null : d.getNote(), locked ? null : file, locked, d.getCreatedAt(), d.getCreatedBy());
    }

    /** A person of the caller's school in their record scope (else not found). */
    private Person person(UUID personId) {
        UUID school = schoolContext.currentSchoolId();
        return people.findById(personId).filter(p -> p.getSchoolId().equals(school))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
