package kh.cmt.payroll.infrastructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import kh.cmt.payroll.domain.PayRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Pay history rows (limited to the caller's record scope). */
public interface PayRateRepository extends JpaRepository<PayRate, UUID> {

    /** Newest first. */
    List<PayRate> findByPersonIdOrderByEffectiveFromDesc(UUID personId);

    /** For each of these people, every row that has started by {@code day}; the newest per person is in force. */
    @Query("select r from PayRate r where r.personId in :people and r.effectiveFrom <= :day order by r.personId, r.effectiveFrom desc")
    List<PayRate> startedBy(Collection<UUID> people, LocalDate day);
}
