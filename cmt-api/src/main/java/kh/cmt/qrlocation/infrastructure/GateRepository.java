package kh.cmt.qrlocation.infrastructure;

import java.util.List;
import java.util.UUID;
import kh.cmt.qrlocation.domain.Gate;
import org.springframework.data.jpa.repository.JpaRepository;

/** Gates; the "active only" filter is on unless the Inactive tab switches it off. */
public interface GateRepository extends JpaRepository<Gate, UUID> {

    List<Gate> findBySchoolIdAndInactiveOrderByNameAsc(UUID schoolId, boolean inactive);

    boolean existsBySchoolIdAndNameIgnoreCaseAndIdNot(UUID schoolId, String name, UUID exceptId);

    /** Active gates of an area (the filter leaves inactive ones out). */
    long countByAreaId(UUID areaId);
}
