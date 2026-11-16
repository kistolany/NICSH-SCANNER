package kh.cmt.qrlocation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;

/**
 * One QR code version of a gate. Open ({@code expiresAt} null) until a new version replaces it (it then expires 5
 * minutes later, so staff scanning while the new sheet is being posted still get in), an admin expires it, or the gate
 * is moved to inactive. Only the salt and a hash of the printed signature are kept (see {@code QrSigner}).
 */
@Entity
@Table(name = "gate_qr")
public class GateQr extends BaseEntity {

    public enum ExpiredReason { REPLACED, MANUAL, GATE_INACTIVE }

    /** ACTIVE: open; EXPIRING: replaced or expired, still accepted until {@code expiresAt}; EXPIRED. */
    public enum Status { ACTIVE, EXPIRING, EXPIRED }

    @Column(name = "gate_id", nullable = false, updatable = false)
    private UUID gateId;

    @Column(name = "qr_version", nullable = false, updatable = false)
    private int qrVersion;

    @Column(nullable = false, updatable = false)
    private String salt;

    @Column(name = "sig_hash", nullable = false, updatable = false)
    private String sigHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "expired_reason")
    private ExpiredReason expiredReason;

    @Column(name = "expired_by")
    private UUID expiredBy;

    protected GateQr() {
    }

    public GateQr(UUID gateId, int qrVersion, String salt, String sigHash) {
        this.gateId = gateId;
        this.qrVersion = qrVersion;
        this.salt = salt;
        this.sigHash = sigHash;
    }

    /** Ends it at {@code at} unless it already ends sooner. */
    public void expire(Instant at, ExpiredReason reason, UUID by) {
        if (expiresAt == null || at.isBefore(expiresAt)) {
            expiresAt = at;
            expiredReason = reason;
            expiredBy = by;
        }
    }

    public Status status(Instant now) {
        if (expiresAt == null) {
            return Status.ACTIVE;
        }
        return now.isBefore(expiresAt) ? Status.EXPIRING : Status.EXPIRED;
    }

    /** Is a scan made at {@code at} accepted? */
    public boolean acceptedAt(Instant at) {
        return expiresAt == null || at.isBefore(expiresAt);
    }

    public UUID getGateId() {
        return gateId;
    }

    public int getQrVersion() {
        return qrVersion;
    }

    public String getSalt() {
        return salt;
    }

    public String getSigHash() {
        return sigHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ExpiredReason getExpiredReason() {
        return expiredReason;
    }

    public UUID getExpiredBy() {
        return expiredBy;
    }
}
