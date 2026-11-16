package kh.cmt.qrlocation.application;

import java.math.BigDecimal;
import java.util.UUID;

/** What the Locations &amp; QR page sends. {@code version}: the one read, on changes. */
public final class LocationCommands {

    private LocationCommands() {
    }

    /** {@code latitude} / {@code longitude} WGS 84 degrees (6 decimals kept); {@code radiusMeters} 20–1000. */
    public record SaveArea(String name, BigDecimal latitude, BigDecimal longitude, Integer radiusMeters, Long version) {
    }

    public record SaveGate(String name, UUID areaId, Long version) {
    }
}
