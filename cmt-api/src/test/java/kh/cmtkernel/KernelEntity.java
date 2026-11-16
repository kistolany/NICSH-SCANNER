package kh.cmtkernel;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.i18n.LocalizedText;

/** Test-only entity (outside kh.cmt so the real app never scans it). */
@Entity
@Table(name = "kernel_test_item")
public class KernelEntity extends SoftDeletable {

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "km", column = @Column(name = "name_km")),
            @AttributeOverride(name = "en", column = @Column(name = "name_en")),
            @AttributeOverride(name = "ar", column = @Column(name = "name_ar"))
    })
    private LocalizedText name;

    protected KernelEntity() {
    }

    public KernelEntity(LocalizedText name) {
        this.name = name;
    }

    public LocalizedText getName() {
        return name;
    }
}
