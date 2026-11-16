package kh.cmt.qrlocation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What the Locations &amp; QR page shows. */
public final class LocationViews {

    private LocationViews() {
    }

    /** {@code gates}: active gates in the area. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Area(UUID id, String name, BigDecimal latitude, BigDecimal longitude, int radiusMeters, long gates, boolean inactive,
            Instant deletedAt, UUID deletedBy, String deleteReason, long version, Instant createdAt, Instant updatedAt) {
    }

    /** {@code qr}: the QR version in use (null until "Create QR"). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gate(UUID id, String name, AreaRef area, QrRef qr, boolean inactive, Instant deletedAt, UUID deletedBy, String deleteReason,
            long version, Instant createdAt, Instant updatedAt) {
    }

    public record QrRef(UUID id, int version, Instant createdAt) {
    }

    /** The gate's area; {@code inactive} when it was moved to inactive since. */
    public record AreaRef(UUID id, String name, boolean inactive) {
    }
}
