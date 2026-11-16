package kh.cmt.qrlocation.application;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.qrlocation.api.Locations;
import kh.cmt.qrlocation.domain.Gate;
import kh.cmt.qrlocation.domain.GateQr;
import kh.cmt.qrlocation.domain.SchoolArea;
import kh.cmt.qrlocation.infrastructure.GateRepository;
import kh.cmt.qrlocation.infrastructure.SchoolAreaRepository;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locations &amp; QR: school areas (where a check-in counts) and gates.
 * <p>
 * Area: a name (unique among active areas), a centre (latitude −90…90, longitude −180…180, 6 decimals kept) and a
 * radius of 20–1000 m (the page's slider gives 50–300). Gate: a name (unique among active gates) in an active area.
 * An area with active gates cannot be moved to inactive (move or retire its gates first, {@code IN_USE}); a gate comes
 * back only into an active area. Delete forever: Super Admin, inactive only, refused while anything refers to it.
 * <p>
 * Permissions: the QR module (Locations &amp; QR) view / create / edit (also restore) / delete.
 */
@Service
@Transactional
public class LocationService implements Locations {

    static final int MIN_RADIUS = 20;
    static final int MAX_RADIUS = 1000;
    static final int MAX_NAME = 60;

    private final SchoolAreaRepository areas;
    private final GateRepository gates;
    private final SchoolContext schoolContext;
    private final EntityManager em;
    private final Clock clock;
    private final QrService qr;

    LocationService(SchoolAreaRepository areas, GateRepository gates, SchoolContext schoolContext, EntityManager em, Clock clock,
            QrService qr) {
        this.areas = areas;
        this.gates = gates;
        this.schoolContext = schoolContext;
        this.em = em;
        this.clock = clock;
        this.qr = qr;
    }

    // ------------------------------------------------------------------ areas

    /** {@code status} active (default) | inactive; by name. */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.VIEW)
    public List<LocationViews.Area> listAreas(String status) {
        boolean inactive = inactiveTab(status);
        List<SchoolArea> found = areas.findBySchoolIdAndInactiveOrderByNameAsc(school(), inactive);
        return found.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.VIEW)
    public LocationViews.Area getArea(UUID id) {
        return view(loadArea(id));
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.CREATE)
    public LocationViews.Area createArea(LocationCommands.SaveArea command) {
        SchoolArea area = new SchoolArea();
        apply(area, command, null);
        return view(save(area, areas));
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.EDIT)
    public LocationViews.Area updateArea(UUID id, LocationCommands.SaveArea command) {
        SchoolArea area = loadArea(id);
        checkVersion(area, command.version());
        apply(area, command, id);
        return view(save(area, areas));
    }

    /** Moves the area to inactive; refused while it has active gates. Repeating it changes nothing. */
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.DELETE)
    public void inactivateArea(UUID id, String reason) {
        SchoolArea area = loadArea(id);
        if (!area.isInactive()) {
            if (gates.countByAreaId(id) > 0) {
                throw new ApiException(ErrorCode.IN_USE);
            }
            inactivate(area, reason);
            save(area, areas);
        }
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.EDIT)
    public LocationViews.Area restoreArea(UUID id) {
        SchoolArea area = loadArea(id);
        if (area.isInactive()) {
            if (areas.existsBySchoolIdAndNameIgnoreCaseAndIdNot(area.getSchoolId(), area.getName(), id)) {
                throw new ApiException(ErrorCode.NAME_TAKEN);   // the name was given to another one meanwhile
            }
            area.restore();
            area = save(area, areas);
        }
        return view(area);
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.DELETE)
    public void deleteAreaForever(UUID id) {
        deleteForever(loadArea(id), areas);
    }

    // ------------------------------------------------------------------ gates

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.VIEW)
    public List<LocationViews.Gate> listGates(String status) {
        boolean inactive = inactiveTab(status);
        List<Gate> found = gates.findBySchoolIdAndInactiveOrderByNameAsc(school(), inactive);
        Map<UUID, SchoolArea> byId = new HashMap<>();
        Map<UUID, GateQr> open = qr.openOf(found.stream().map(Gate::getId).toList());
        return found.stream().map(g -> view(g, byId, open)).toList();
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.VIEW)
    public LocationViews.Gate getGate(UUID id) {
        return view(loadGate(id));
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.CREATE)
    public LocationViews.Gate createGate(LocationCommands.SaveGate command) {
        Gate gate = new Gate();
        apply(gate, command, null);
        return view(save(gate, gates));
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.EDIT)
    public LocationViews.Gate updateGate(UUID id, LocationCommands.SaveGate command) {
        Gate gate = loadGate(id);
        checkVersion(gate, command.version());
        apply(gate, command, id);
        return view(save(gate, gates));
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.DELETE)
    public void inactivateGate(UUID id, String reason) {
        Gate gate = loadGate(id);
        if (!gate.isInactive()) {
            inactivate(gate, reason);
            save(gate, gates);
            qr.gateRetired(id);   // its QR stops working now
        }
    }

    /** Brings a gate back; its area must be active (restore the area first, {@code CONFLICT}). */
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.EDIT)
    public LocationViews.Gate restoreGate(UUID id) {
        Gate gate = loadGate(id);
        if (gate.isInactive()) {
            if (areas.findById(gate.getAreaId()).map(SoftDeletable::isInactive).orElse(true)) {
                throw new ApiException(ErrorCode.CONFLICT);
            }
            if (gates.existsBySchoolIdAndNameIgnoreCaseAndIdNot(gate.getSchoolId(), gate.getName(), id)) {
                throw new ApiException(ErrorCode.NAME_TAKEN);
            }
            gate.restore();
            gate = save(gate, gates);
        }
        return view(gate);
    }

    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.DELETE)
    public void deleteGateForever(UUID id) {
        deleteForever(loadGate(id), gates);
    }

    // ------------------------------------------------------------------ for other modules

    @Override
    @Transactional(readOnly = true)
    public Optional<AreaHit> locate(double latitude, double longitude) {
        return areas.findBySchoolIdAndInactiveOrderByNameAsc(school(), false).stream()
                .map(a -> {
                    double d = a.distanceTo(latitude, longitude);
                    return new AreaHit(a.getId(), a.getName(), a.getRadiusMeters(), d, d <= a.getRadiusMeters(),
                            Math.max(0, d - a.getRadiusMeters()));
                })
                // inside first; then the smallest distance past the edge; then the nearest centre
                .min(Comparator.comparing((AreaHit h) -> !h.inside()).thenComparingDouble(AreaHit::metersOutside)
                        .thenComparingDouble(AreaHit::distanceMeters));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AreaHit> distanceTo(UUID areaId, double latitude, double longitude) {
        return areas.findById(areaId).filter(a -> a.getSchoolId().equals(school())).map(a -> {
            double d = a.distanceTo(latitude, longitude);
            return new AreaHit(a.getId(), a.getName(), a.getRadiusMeters(), d, d <= a.getRadiusMeters(), Math.max(0, d - a.getRadiusMeters()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GateInfo> gate(UUID gateId) {
        return gates.findById(gateId).filter(g -> g.getSchoolId().equals(school()))
                .map(g -> new GateInfo(g.getId(), g.getName(), g.getAreaId(), !g.isInactive()));
    }

    // ------------------------------------------------------------------ checks

    private void apply(SchoolArea area, LocationCommands.SaveArea c, UUID exceptId) {
        List<Map<String, String>> errors = new ArrayList<>();
        String name = name(c.name(), errors);
        BigDecimal lat = coordinate(c.latitude(), "latitude", 90, errors);
        BigDecimal lng = coordinate(c.longitude(), "longitude", 180, errors);
        if (c.radiusMeters() == null) {
            errors.add(error("radiusMeters", "NotNull"));
        } else if (c.radiusMeters() < MIN_RADIUS || c.radiusMeters() > MAX_RADIUS) {
            errors.add(error("radiusMeters", "Range"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        if (areas.existsBySchoolIdAndNameIgnoreCaseAndIdNot(school(), name, exceptId == null ? new UUID(0, 0) : exceptId)) {
            throw new ApiException(ErrorCode.NAME_TAKEN);
        }
        area.set(name, lat, lng, c.radiusMeters());
    }

    private void apply(Gate gate, LocationCommands.SaveGate c, UUID exceptId) {
        List<Map<String, String>> errors = new ArrayList<>();
        String name = name(c.name(), errors);
        if (c.areaId() == null) {
            errors.add(error("areaId", "NotNull"));
        } else if (areas.findById(c.areaId()).filter(a -> a.getSchoolId().equals(school()) && !a.isInactive()).isEmpty()) {
            errors.add(error("areaId", "UnknownArea"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        if (gates.existsBySchoolIdAndNameIgnoreCaseAndIdNot(school(), name, exceptId == null ? new UUID(0, 0) : exceptId)) {
            throw new ApiException(ErrorCode.NAME_TAKEN);
        }
        gate.set(name, c.areaId());
    }

    private static String name(String in, List<Map<String, String>> errors) {
        String name = in == null ? "" : in.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            errors.add(error("name", "NotBlank"));
        } else if (name.length() > MAX_NAME) {
            errors.add(error("name", "Size"));
        }
        return name;
    }

    private static BigDecimal coordinate(BigDecimal in, String field, int limit, List<Map<String, String>> errors) {
        if (in == null) {
            errors.add(error(field, "NotNull"));
            return null;
        }
        BigDecimal value = in.setScale(6, RoundingMode.HALF_UP);
        if (value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
            errors.add(error(field, "Range"));
        }
        return value;
    }

    private static void checkVersion(SoftDeletable item, Long version) {
        if (item.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);   // restore first
        }
        if (version == null) {
            throw validation(List.of(error("version", "NotNull")));
        }
        if (version != item.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void inactivate(SoftDeletable item, String reason) {
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        item.inactivate(CurrentUser.require().userId(), clock.instant(), why);
    }

    private <T extends SoftDeletable> void deleteForever(T item, org.springframework.data.jpa.repository.JpaRepository<T, UUID> repository) {
        if (!"super_admin".equals(CurrentUser.require().role())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (!item.isInactive()) {
            throw new ApiException(ErrorCode.NOT_INACTIVE);
        }
        try {
            repository.delete(item);
            repository.flush();
        } catch (DataIntegrityViolationException stillUsed) {
            throw new ApiException(ErrorCode.IN_USE);
        }
    }

    private static <T extends SoftDeletable> T save(T item, org.springframework.data.jpa.repository.JpaRepository<T, UUID> repository) {
        try {
            return repository.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.NAME_TAKEN);   // a parallel request took the name first
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean inactiveTab(String status) {
        boolean inactive = "inactive".equals(status);
        if (status != null && !status.isBlank() && !inactive && !"active".equals(status)) {
            throw validation(List.of(error("status", "OneOf")));
        }
        if (inactive) {
            SoftDeleteFilter.includeInactive(em);
        }
        return inactive;
    }

    private SchoolArea loadArea(UUID id) {
        return areas.findById(id).filter(a -> a.getSchoolId().equals(school())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Gate loadGate(UUID id) {
        return gates.findById(id).filter(g -> g.getSchoolId().equals(school())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private LocationViews.Area view(SchoolArea a) {
        return new LocationViews.Area(a.getId(), a.getName(), a.getLatitude(), a.getLongitude(), a.getRadiusMeters(),
                a.isInactive() ? 0 : gates.countByAreaId(a.getId()), a.isInactive(), a.getDeletedAt(), a.getDeletedBy(), a.getDeleteReason(),
                a.getVersion(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private LocationViews.Gate view(Gate g) {
        return view(g, new HashMap<>(), qr.openOf(List.of(g.getId())));
    }

    private LocationViews.Gate view(Gate g, Map<UUID, SchoolArea> cache, Map<UUID, GateQr> open) {
        SchoolArea area = cache.computeIfAbsent(g.getAreaId(), id -> areas.findById(id).orElse(null));
        LocationViews.AreaRef ref = area == null ? null : new LocationViews.AreaRef(area.getId(), area.getName(), area.isInactive());
        GateQr q = open.get(g.getId());
        LocationViews.QrRef qrRef = q == null ? null : new LocationViews.QrRef(q.getId(), q.getQrVersion(), q.getCreatedAt());
        return new LocationViews.Gate(g.getId(), g.getName(), ref, qrRef, g.isInactive(), g.getDeletedAt(), g.getDeletedBy(),
                g.getDeleteReason(), g.getVersion(), g.getCreatedAt(), g.getUpdatedAt());
    }

    private UUID school() {
        return schoolContext.currentSchoolId();
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
