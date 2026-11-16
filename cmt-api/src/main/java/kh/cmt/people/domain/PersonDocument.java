package kh.cmt.people.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import kh.cmt.shared.security.SensitiveField;
import org.hibernate.annotations.Filter;

/** A document of a person (national ID scan, contract, degree...): the uploaded file, issue and expiry dates. */
@Entity
@Table(name = "person_document")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
public class PersonDocument extends BaseEntity {

    /** The kinds of document, and the sensitive-field group a role needs to see each. */
    public enum Type {
        NATIONAL_ID_FRONT(SensitiveField.ID_SCANS), NATIONAL_ID_BACK(SensitiveField.ID_SCANS),
        CONTRACT(SensitiveField.NATIONAL_ID), DEGREE(SensitiveField.NATIONAL_ID), CV(SensitiveField.NATIONAL_ID),
        HEALTH_CERT(SensitiveField.NATIONAL_ID), POLICE_CLEARANCE(SensitiveField.NATIONAL_ID), OTHER(SensitiveField.NATIONAL_ID);

        private final SensitiveField group;

        Type(SensitiveField group) {
            this.group = group;
        }

        /** National ID scans: "See national ID scans"; every other document: "See national ID &amp; documents". */
        public SensitiveField group() {
            return group;
        }
    }

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Type type;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    private String note;

    public UUID getPersonId() {
        return personId;
    }

    public void setPersonId(UUID personId) {
        this.personId = personId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public UUID getFileId() {
        return fileId;
    }

    public void setFileId(UUID fileId) {
        this.fileId = fileId;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
