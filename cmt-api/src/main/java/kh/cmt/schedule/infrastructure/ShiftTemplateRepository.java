package kh.cmt.schedule.infrastructure;

import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.domain.ShiftTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/** Shift templates; the "active only" filter is on unless the Inactive tab switches it off. */
public interface ShiftTemplateRepository extends JpaRepository<ShiftTemplate, UUID> {

    List<ShiftTemplate> findBySchoolIdAndInactiveOrderByNameAsc(UUID schoolId, boolean inactive);

    boolean existsBySchoolIdAndNameIgnoreCaseAndIdNot(UUID schoolId, String name, UUID exceptId);
}
