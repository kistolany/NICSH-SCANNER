package kh.cmt.org.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.UUID;
import kh.cmt.shared.people.PeopleCounts;
import kh.cmt.shared.security.SchoolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Shared tools of the org item types. */
@Component
class OrgSupport {

    @PersistenceContext
    private EntityManager entityManager;

    private final Clock clock;
    private final SchoolContext schoolContext;
    private final ObjectProvider<PeopleCounts> peopleCounts;

    OrgSupport(Clock clock, SchoolContext schoolContext, ObjectProvider<PeopleCounts> peopleCounts) {
        this.clock = clock;
        this.schoolContext = schoolContext;
        this.peopleCounts = peopleCounts;
    }

    EntityManager entityManager() {
        return entityManager;
    }

    Clock clock() {
        return clock;
    }

    UUID schoolId() {
        return schoolContext.currentSchoolId();
    }

    /** Provided by the people module (which depends on org, so it is looked up, not injected directly). */
    PeopleCounts peopleCounts() {
        return peopleCounts.getObject();
    }
}
