package kh.cmt.qrlocation.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask about school areas and gates (the scan, step 7.2; QR codes, 6.2). Answers are for the
 * caller's school.
 */
public interface Locations {

    /**
     * Where a point is: the active area it is inside (the nearest centre when it is inside several), else the nearest
     * area with how far outside its edge the point is. Empty when the school has no active area.
     */
    Optional<AreaHit> locate(double latitude, double longitude);

    /**
     * {@code distanceMeters} from the area's centre; {@code inside} when within the radius;
     * {@code metersOutside} how far past the edge (0 when inside).
     */
    record AreaHit(UUID areaId, String name, int radiusMeters, double distanceMeters, boolean inside, double metersOutside) {
    }

    /** Where a point is against one area of the school (e.g. the area of the gate whose QR was scanned). */
    Optional<AreaHit> distanceTo(UUID areaId, double latitude, double longitude);

    /** A gate of the school, inactive ones too ({@code active} false). */
    Optional<GateInfo> gate(UUID gateId);

    record GateInfo(UUID id, String name, UUID areaId, boolean active) {
    }
}
