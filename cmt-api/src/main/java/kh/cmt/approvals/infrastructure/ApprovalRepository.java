package kh.cmt.approvals.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.approvals.domain.Approval;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Record scope applies (by the person who asked). */
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findFirstByTypeAndSubjectIdOrderByRequestedAtDesc(Approvals.Type type, UUID subjectId);

    List<Approval> findByTypeAndSubjectIdInOrderByRequestedAtDesc(Approvals.Type type, Collection<UUID> subjectIds);

    List<Approval> findBySchoolIdAndStatusOrderByRequestedAtAsc(UUID schoolId, Approvals.Status status);

    List<Approval> findBySchoolIdAndStatusInOrderByDecidedAtDesc(UUID schoolId, Collection<Approvals.Status> statuses, Pageable page);

    /** Every school's decisions whose 10 s are over (the effects job). */
    @Query("select a.id from Approval a where a.appliedAt is null and a.effectiveAt <= :now order by a.effectiveAt")
    List<UUID> due(Instant now, Pageable page);

    /** The person's requests of a type sent since a moment, cancelled ones left out ("3 corrections this month"). */
    @Query("select count(a) from Approval a where a.personId = :person and a.type = :type and a.requestedAt >= :since"
            + " and a.status <> kh.cmt.approvals.api.Approvals.Status.CANCELLED")
    long countSince(UUID person, Approvals.Type type, Instant since);
}
