package kh.cmt.attendance.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.attendance.domain.ScanEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scans, as they came in. Only ever added to (the database refuses changes and removals). */
public interface ScanEventRepository extends JpaRepository<ScanEvent, UUID> {

    /** The scan with this key (an offline scan sent again is the same scan). */
    Optional<ScanEvent> findByIdempotencyKey(String idempotencyKey);

    /** The people whose scans got this result in {@code from} – {@code to} (e.g. "Outside area" today). */
    @Query("select distinct e.personId from ScanEvent e where e.receivedAt >= :from and e.receivedAt < :to and e.result = :result")
    Set<UUID> peopleWithResult(Instant from, Instant to, String result);

    /** A person's last scan since {@code from}, whatever came of it (the Today card's "Outside area"). */
    Optional<ScanEvent> findFirstByPersonIdAndReceivedAtGreaterThanEqualOrderByReceivedAtDesc(UUID personId, Instant from);
}
