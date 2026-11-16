package kh.cmt.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One scan as it came in and what the server decided ({@code result}: ACCEPTED or the refusal code). Never changed or
 * removed (the database refuses it). The same {@code idempotencyKey} twice is the same scan (an offline scan sent
 * again). The QR itself is not kept (it is a key to check in): only a hash of it, the gate and the version it named.
 */
@Entity
@Immutable
@Table(name = "scan_event")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScanEvent extends BaseEntity {

    public static final String ACCEPTED = "ACCEPTED";

    public enum Source { ONLINE, OFFLINE }

    /** What a scan was: everything known about it when it is stored. */
    public record Facts(UUID personId, UUID accountId, UUID deviceId, String idempotencyKey, Source source, Instant clientTime,
            Instant receivedAt, String qrPayloadHash, UUID gateId, Integer qrVersion, BigDecimal latitude, BigDecimal longitude,
            BigDecimal accuracyMeters, BigDecimal distanceMeters, UUID areaId, Boolean mockLocation, Boolean rooted, Boolean signatureOk,
            Kind kind, String result, UUID recordId, Integer shiftIdx, String earlyReason) {
    }

    public enum Kind { CHECK_IN, CHECK_OUT }

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "device_id", updatable = false)
    private UUID deviceId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    private Source source;

    @Column(name = "client_time", nullable = false, updatable = false)
    private Instant clientTime;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "qr_payload_hash", updatable = false)
    private String qrPayloadHash;

    @Column(name = "gate_id", updatable = false)
    private UUID gateId;

    @Column(name = "qr_version", updatable = false)
    private Integer qrVersion;

    @Column(precision = 9, scale = 6, updatable = false)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6, updatable = false)
    private BigDecimal longitude;

    @Column(name = "accuracy_m", precision = 8, scale = 1, updatable = false)
    private BigDecimal accuracyMeters;

    @Column(name = "distance_m", precision = 10, scale = 1, updatable = false)
    private BigDecimal distanceMeters;

    @Column(name = "area_id", updatable = false)
    private UUID areaId;

    @Column(name = "mock_location", updatable = false)
    private Boolean mockLocation;

    @Column(updatable = false)
    private Boolean rooted;

    @Column(name = "signature_ok", updatable = false)
    private Boolean signatureOk;

    @Column(updatable = false)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(nullable = false, updatable = false)
    private String result;

    @Column(name = "record_id", updatable = false)
    private UUID recordId;

    @Column(name = "shift_idx", updatable = false)
    private Integer shiftIdx;

    @Column(name = "early_reason", updatable = false)
    private String earlyReason;

    /** What a refused scan was told besides its code (answered again as is when the scan is sent again). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> details;

    public ScanEvent(Facts f, Map<String, Object> details) {
        this(f);
        this.details = details == null || details.isEmpty() ? null : new LinkedHashMap<>(details);
    }

    public ScanEvent(Facts f) {
        this.personId = f.personId();
        this.accountId = f.accountId();
        this.deviceId = f.deviceId();
        this.idempotencyKey = f.idempotencyKey();
        this.source = f.source();
        this.clientTime = f.clientTime();
        this.receivedAt = f.receivedAt();
        this.qrPayloadHash = f.qrPayloadHash();
        this.gateId = f.gateId();
        this.qrVersion = f.qrVersion();
        this.latitude = f.latitude();
        this.longitude = f.longitude();
        this.accuracyMeters = f.accuracyMeters();
        this.distanceMeters = f.distanceMeters();
        this.areaId = f.areaId();
        this.mockLocation = f.mockLocation();
        this.rooted = f.rooted();
        this.signatureOk = f.signatureOk();
        this.kind = f.kind();
        this.result = f.result();
        this.recordId = f.recordId();
        this.shiftIdx = f.shiftIdx();
        this.earlyReason = f.earlyReason();
    }

    public boolean accepted() {
        return ACCEPTED.equals(result);
    }

    public Map<String, Object> getDetails() {
        return details == null ? Map.of() : details;
    }
}
