package kh.cmt.leave.infrastructure;

import java.util.List;
import java.util.UUID;
import kh.cmt.leave.domain.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The "activeOnly" filter applies: inactive types are left out unless it is turned off. */
public interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {

    List<LeaveType> findBySchoolIdOrderByPositionAscNameEnAsc(UUID schoolId);

    boolean existsBySchoolIdAndNameEnIgnoreCaseAndIdNot(UUID schoolId, String nameEn, UUID exceptId);

    boolean existsBySchoolIdAndCode(UUID schoolId, String code);

    @Query("select coalesce(max(t.position), 0) from LeaveType t where t.schoolId = :schoolId")
    int lastPosition(UUID schoolId);
}
