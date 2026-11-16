package kh.cmt.org.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.MappedSuperclass;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.i18n.LocalizedText;

/** An org item with a name in Khmer, English and Arabic (English is required). */
@MappedSuperclass
public abstract class LocalizedOrgItem extends SoftDeletable {

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "km", column = @Column(name = "name_km")),
            @AttributeOverride(name = "en", column = @Column(name = "name_en")),
            @AttributeOverride(name = "ar", column = @Column(name = "name_ar"))
    })
    private LocalizedText name;

    public LocalizedText getName() {
        return name;
    }

    public void setName(LocalizedText name) {
        this.name = name;
    }
}
