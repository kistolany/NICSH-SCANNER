package kh.cmt.qrlocation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** QR codes on the Locations &amp; QR page. */
public final class QrViews {

    private QrViews() {
    }

    /**
     * One version (a row of QR history). {@code status} ACTIVE | EXPIRING | EXPIRED; {@code secondsLeft} while EXPIRING;
     * {@code expiredReason} REPLACED | MANUAL | GATE_INACTIVE.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Qr(UUID id, GateRef gate, int version, String status, Instant expiresAt, Long secondsLeft, String expiredReason,
            Instant createdAt, UUID createdBy, String createdByName) {
    }

    public record GateRef(UUID id, String name, boolean inactive) {
    }

    /** After "Create QR": the new version, and the one it replaced (expiring in 5 minutes), if any. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Created(Qr qr, Qr replaced) {
    }

    public record History(List<Qr> items, int page, int size, long total) {
    }

    /** A printable sheet: bytes, content type, file name. */
    public record Sheet(byte[] bytes, String contentType, String fileName) {
    }
}
