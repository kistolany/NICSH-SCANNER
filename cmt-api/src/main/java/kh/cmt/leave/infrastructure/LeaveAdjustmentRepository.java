package kh.cmt.leave.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import kh.cmt.leave.domain.LeaveAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveAdjustmentRepository extends JpaRepository<LeaveAdjustment, UUID> {

    List<LeaveAdjustment> findByPersonIdInAndYear(Collection<UUID> personIds, int year);

    List<LeaveAdjustment> findByPersonIdAndYearOrderByCreatedAtDesc(UUID personId, int year);
}
