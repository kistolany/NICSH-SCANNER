package kh.cmt.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.Hibernate;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Columns every table has: id, school_id, created/updated at/by, version (optimistic lock).
 * <p>
 * Also defines the record-scope filter (see {@link ScopeFilter}): it is on in every session and only affects the
 * entities that declare a condition for it.
 */
@FilterDef(name = ScopeFilter.NAME, autoEnabled = true, applyToLoadByKey = true, parameters = {
        @ParamDef(name = "scopeMode", type = String.class, resolver = ScopeFilter.ModeParameter.class),
        @ParamDef(name = "scopeDepartments", type = String.class, resolver = ScopeFilter.DepartmentsParameter.class),
        @ParamDef(name = "scopePerson", type = UUID.class, resolver = ScopeFilter.PersonParameter.class)
})
@MappedSuperclass
@EntityListeners({AuditingEntityListener.class, SchoolIdListener.class})
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(nullable = false)
    private long version;

    public UUID getId() {
        return id;
    }

    public UUID getSchoolId() {
        return schoolId;
    }

    void setSchoolId(UUID schoolId) {
        this.schoolId = schoolId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaseEntity other) || id == null) {
            return false;
        }
        return Hibernate.getClass(this) == Hibernate.getClass(other) && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
