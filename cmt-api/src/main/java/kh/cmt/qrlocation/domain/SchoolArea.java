package kh.cmt.qrlocation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import kh.cmt.shared.domain.SoftDeletable;

/**
 * Where checking in counts: a circle around a centre point (WGS 84 latitude / longitude, 6 decimals ≈ 10 cm) with a
 * radius in metres. A school may have several; they may overlap.
 */
@Entity
@Table(name = "school_area")
public class SchoolArea extends SoftDeletable {

    /** Mean radius of the Earth (IUGG), metres. */
    static final double EARTH_RADIUS_M = 6_371_008.8;

    private String name;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "radius_m")
    private int radiusMeters;

    public void set(String name, BigDecimal latitude, BigDecimal longitude, int radiusMeters) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public int getRadiusMeters() {
        return radiusMeters;
    }

    /** Great-circle distance in metres from the centre to a point (haversine). */
    public double distanceTo(double lat, double lng) {
        return distance(latitude.doubleValue(), longitude.doubleValue(), lat, lng);
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
