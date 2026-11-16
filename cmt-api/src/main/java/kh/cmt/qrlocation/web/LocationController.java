package kh.cmt.qrlocation.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import kh.cmt.qrlocation.application.LocationCommands;
import kh.cmt.qrlocation.application.LocationService;
import kh.cmt.qrlocation.application.LocationViews;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Locations &amp; QR: school areas and gates.
 * <pre>
 * GET    /api/v1/admin/areas?status=active|inactive      by name, with the number of active gates
 * GET    /api/v1/admin/areas/{id}
 * POST   /api/v1/admin/areas                             {name, latitude, longitude, radiusMeters}
 * PUT    /api/v1/admin/areas/{id}                        the same + version
 * DELETE /api/v1/admin/areas/{id}?reason=                move to inactive (IN_USE while it has active gates)
 * POST   /api/v1/admin/areas/{id}/restore
 * DELETE /api/v1/admin/areas/{id}/forever                Super Admin, inactive only
 * GET / POST / PUT / DELETE /api/v1/admin/gates…         the same for gates: {name, areaId}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin")
class LocationController {

    private final LocationService service;

    LocationController(LocationService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ areas

    @GetMapping("/areas")
    List<LocationViews.Area> areas(@RequestParam(required = false) String status) {
        return service.listAreas(status);
    }

    @GetMapping("/areas/{id}")
    LocationViews.Area area(@PathVariable UUID id) {
        return service.getArea(id);
    }

    @PostMapping("/areas")
    ResponseEntity<LocationViews.Area> createArea(@RequestBody LocationCommands.SaveArea command) {
        LocationViews.Area created = service.createArea(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/areas/" + created.id())).body(created);
    }

    @PutMapping("/areas/{id}")
    LocationViews.Area updateArea(@PathVariable UUID id, @RequestBody LocationCommands.SaveArea command) {
        return service.updateArea(id, command);
    }

    @DeleteMapping("/areas/{id}")
    ResponseEntity<Void> inactivateArea(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        service.inactivateArea(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/areas/{id}/restore")
    LocationViews.Area restoreArea(@PathVariable UUID id) {
        return service.restoreArea(id);
    }

    @DeleteMapping("/areas/{id}/forever")
    ResponseEntity<Void> deleteAreaForever(@PathVariable UUID id) {
        service.deleteAreaForever(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ gates

    @GetMapping("/gates")
    List<LocationViews.Gate> gates(@RequestParam(required = false) String status) {
        return service.listGates(status);
    }

    @GetMapping("/gates/{id}")
    LocationViews.Gate gate(@PathVariable UUID id) {
        return service.getGate(id);
    }

    @PostMapping("/gates")
    ResponseEntity<LocationViews.Gate> createGate(@RequestBody LocationCommands.SaveGate command) {
        LocationViews.Gate created = service.createGate(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/gates/" + created.id())).body(created);
    }

    @PutMapping("/gates/{id}")
    LocationViews.Gate updateGate(@PathVariable UUID id, @RequestBody LocationCommands.SaveGate command) {
        return service.updateGate(id, command);
    }

    @DeleteMapping("/gates/{id}")
    ResponseEntity<Void> inactivateGate(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        service.inactivateGate(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/gates/{id}/restore")
    LocationViews.Gate restoreGate(@PathVariable UUID id) {
        return service.restoreGate(id);
    }

    @DeleteMapping("/gates/{id}/forever")
    ResponseEntity<Void> deleteGateForever(@PathVariable UUID id) {
        service.deleteGateForever(id);
        return ResponseEntity.noContent().build();
    }
}
