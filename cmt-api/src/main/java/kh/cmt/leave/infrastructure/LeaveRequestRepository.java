package kh.cmt.leave.infrastructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import kh.cmt.leave.domain.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;

/** Record scope applies (by person). */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    List<LeaveRequest> findByPersonIdOrderByStartDateDescCreatedAtDesc(UUID personId);

    /** Waiting and approved requests of these people that touch the days {@code from} – {@code to}. */
    List<LeaveRequest> findByPersonIdInAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(Collection<UUID> personIds,
            Collection<LeaveRequest.Status> statuses, LocalDate to, LocalDate from);

    /** The requests of these people in a year (a request never crosses into another year). */
    List<LeaveRequest> findByPersonIdInAndStatusInAndStartDateBetween(Collection<UUID> personIds, Collection<LeaveRequest.Status> statuses,
            LocalDate from, LocalDate to);
}
