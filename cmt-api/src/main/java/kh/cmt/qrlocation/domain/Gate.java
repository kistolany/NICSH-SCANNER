package kh.cmt.qrlocation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import kh.cmt.shared.domain.SoftDeletable;

/** A gate staff check in at ("Main gate"), in one school area. Its QR codes come in step 6.2. */
@Entity
@Table(name = "gate")
public class Gate extends SoftDeletable {

    private String name;

    @Column(name = "area_id")
    private UUID areaId;

    public void set(String name, UUID areaId) {
        this.name = name;
        this.areaId = areaId;
    }

    public String getName() {
        return name;
    }

    public UUID getAreaId() {
        return areaId;
    }
}
