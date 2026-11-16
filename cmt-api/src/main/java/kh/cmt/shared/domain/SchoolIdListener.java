package kh.cmt.shared.domain;

import jakarta.persistence.PrePersist;
import kh.cmt.shared.security.SchoolContext;
import org.springframework.stereotype.Component;

/** Fills {@link BaseEntity#getSchoolId()} on insert when the code did not set it. */
@Component
public class SchoolIdListener {

    private final SchoolContext schoolContext;

    public SchoolIdListener(SchoolContext schoolContext) {
        this.schoolContext = schoolContext;
    }

    @PrePersist
    void assignSchool(BaseEntity entity) {
        if (entity.getSchoolId() == null) {
            entity.setSchoolId(schoolContext.currentSchoolId());
        }
    }
}
