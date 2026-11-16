package kh.cmt.qrlocation.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.qrlocation.domain.GateQr;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** QR code versions of gates. */
public interface GateQrRepository extends JpaRepository<GateQr, UUID> {

    Optional<GateQr> findByGateIdAndQrVersion(UUID gateId, int qrVersion);

    Optional<GateQr> findByGateIdAndExpiresAtIsNull(UUID gateId);

    List<GateQr> findByGateIdInAndExpiresAtIsNull(Collection<UUID> gateIds);

    @Query("select coalesce(max(q.qrVersion), 0) from GateQr q where q.gateId = :gateId")
    int lastVersion(UUID gateId);

    Page<GateQr> findBySchoolIdOrderByCreatedAtDescQrVersionDesc(UUID schoolId, Pageable page);

    Page<GateQr> findBySchoolIdAndGateIdOrderByCreatedAtDescQrVersionDesc(UUID schoolId, UUID gateId, Pageable page);

    /** Every version of a gate that is still open or not yet past its end (to expire them with the gate). */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from GateQr q where q.gateId = :gateId and (q.expiresAt is null or q.expiresAt > :now)")
    List<GateQr> findUsable(UUID gateId, java.time.Instant now);
}
