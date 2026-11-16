package kh.cmt.people.infrastructure;

import java.util.UUID;
import kh.cmt.people.domain.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** People. Every query is limited to the caller's record scope; the "active only" filter is on unless switched off. */
public interface PersonRepository extends JpaRepository<Person, UUID>, JpaSpecificationExecutor<Person> {

    /** Active people of the school within the caller's record scope (both filters apply). */
    java.util.List<Person> findBySchoolId(UUID schoolId);
}
