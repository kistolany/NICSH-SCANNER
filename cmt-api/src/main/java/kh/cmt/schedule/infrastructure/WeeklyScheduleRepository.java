package kh.cmt.schedule.infrastructure;

import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.domain.WeeklySchedule;
import org.springframework.data.jpa.repository.JpaRepository;

/** Weekly schedule versions. */
public interface WeeklyScheduleRepository extends JpaRepository<WeeklySchedule, UUID> {

    /** Every version of the school (a school has few: one per target and change). */
    List<WeeklySchedule> findBySchoolId(UUID schoolId);
}
