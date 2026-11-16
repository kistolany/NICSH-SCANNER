package kh.cmt.shared.security;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single-school mode: always the school from configuration (the CMT row inserted by V1__base.sql).
 * Replace with the {@code school_id} JWT claim when multi-school is needed (step 1.2 adds the claim).
 */
@Component
class ConfiguredSchoolContext implements SchoolContext {

    private final UUID schoolId;

    ConfiguredSchoolContext(@Value("${cmt.school.default-id}") UUID schoolId) {
        this.schoolId = schoolId;
    }

    @Override
    public UUID currentSchoolId() {
        return schoolId;
    }
}
