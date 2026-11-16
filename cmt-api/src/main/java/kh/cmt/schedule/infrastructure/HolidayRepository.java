package kh.cmt.schedule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.schedule.domain.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Holidays. */
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    /** Holidays with at least one day between {@code from} and {@code to}, soonest first. */
    @Query("""
            select h from Holiday h
            where h.schoolId = :school and h.startDate <= :to and h.endDate >= :from
            order by h.startDate, h.endDate""")
    List<Holiday> overlapping(@Param("school") UUID school, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
