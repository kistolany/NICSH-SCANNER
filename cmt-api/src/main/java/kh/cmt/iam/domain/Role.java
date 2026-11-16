package kh.cmt.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.security.ScopeType;

/**
 * A role of the Admin Web. {@code code} is its stable identity (it is what the access token carries); the name
 * can change. A system role is locked. {@code permVersion} goes up whenever the permissions or the record scope
 * change.
 */
@Entity
@Table(name = "role")
public class Role extends SoftDeletable {

    private String code;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "km", column = @Column(name = "name_km")),
            @AttributeOverride(name = "en", column = @Column(name = "name_en")),
            @AttributeOverride(name = "ar", column = @Column(name = "name_ar"))
    })
    private LocalizedText name;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "km", column = @Column(name = "description_km")),
            @AttributeOverride(name = "en", column = @Column(name = "description_en")),
            @AttributeOverride(name = "ar", column = @Column(name = "description_ar"))
    })
    private LocalizedText description;

    private boolean system;

    @Column(name = "perm_version")
    private int permVersion = 1;

    /** Whose records the role's users see; the departments of SELECTED_DEPARTMENTS are in role_scope_department. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type")
    private ScopeType scopeType = ScopeType.ALL;

    /** The role a new web user gets when none is chosen. At most one per school. */
    @Column(name = "is_default")
    private boolean defaultRole;

    public boolean isDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(boolean defaultRole) {
        this.defaultRole = defaultRole;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(ScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalizedText getName() {
        return name;
    }

    public void setName(LocalizedText name) {
        this.name = name;
    }

    public LocalizedText getDescription() {
        return description;
    }

    public void setDescription(LocalizedText description) {
        this.description = description;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    public int getPermVersion() {
        return permVersion;
    }

    public void bumpPermVersion() {
        this.permVersion++;
    }
}
