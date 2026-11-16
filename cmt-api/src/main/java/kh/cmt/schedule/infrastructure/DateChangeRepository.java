package kh.cmt.schedule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.domain.DateChange;
import org.springframework.data.jpa.repository.JpaRepository;

/** Date changes. */
public interface DateChangeRepository extends JpaRepository<DateChange, UUID> {

    List<DateChange> findBySchoolIdAndDateBetweenOrderByDateAsc(UUID schoolId, LocalDate from, LocalDate to);

    List<DateChange> findBySchoolIdAndDateIn(UUID schoolId, List<LocalDate> dates);

    boolean existsBySchoolIdAndTemplateIdAndDateGreaterThanEqual(UUID schoolId, UUID templateId, LocalDate from);

    List<DateChange> findBySchoolIdAndTemplateIdAndDateGreaterThanEqual(UUID schoolId, UUID templateId, LocalDate from);
}
