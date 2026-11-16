package kh.cmt.qrlocation.infrastructure;

import java.util.List;
import java.util.UUID;
import kh.cmt.qrlocation.domain.SchoolArea;
import org.springframework.data.jpa.repository.JpaRepository;

/** School areas; the "active only" filter is on unless the Inactive tab switches it off. */
public interface SchoolAreaRepository extends JpaRepository<SchoolArea, UUID> {

    List<SchoolArea> findBySchoolIdAndInactiveOrderByNameAsc(UUID schoolId, boolean inactive);

    boolean existsBySchoolIdAndNameIgnoreCaseAndIdNot(UUID schoolId, String name, UUID exceptId);
}
