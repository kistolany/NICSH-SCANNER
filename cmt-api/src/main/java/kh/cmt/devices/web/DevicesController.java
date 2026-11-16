package kh.cmt.devices.web;

import java.util.List;
import java.util.UUID;
import kh.cmt.devices.application.DeviceService;
import kh.cmt.devices.application.DeviceViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web › Devices.
 * <pre>
 * GET  /api/v1/admin/devices?tab=all|none&q=&department=&platform=android|ios&status=linked|waiting|none|blocked
 *                            &version=old|ok&idle7=&page=&size=      rows + counts + the school's minimum app version
 * GET  /api/v1/admin/devices/requests?status=open|all                change requests (listing open ones marks them seen)
 * POST /api/v1/admin/devices/requests/{id}/approve                   the phone that asked becomes the linked one
 * POST /api/v1/admin/devices/requests/{id}/reject      {reason}
 * POST /api/v1/admin/devices/{personId}/reset          {reason}      unlink: the next phone they log in on is linked
 * POST /api/v1/admin/devices/{personId}/block          {reason}
 * POST /api/v1/admin/devices/{personId}/unblock
 * GET  /api/v1/admin/devices/history?kind=reset|BLOCKED|…&page=&size=
 * GET  /api/v1/admin/devices/settings
 * PUT  /api/v1/admin/devices/settings                  {oneDevice, changeRequests, autoRebindDays, minAppVersion,
 *                                                       blockRooted, blockMockLocation, version}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/devices")
class DevicesController {

    private final DeviceService service;

    DevicesController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    DeviceViews.Page list(@RequestParam(required = false) String tab, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID department, @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status, @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "false") boolean idle7, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.list(new DeviceService.Filter(tab, q, department, platform, status, version, idle7), page, size);
    }

    @GetMapping("/requests")
    List<DeviceViews.Request> requests(@RequestParam(required = false) String status) {
        return service.requests(status);
    }

    @PostMapping("/requests/{id}/approve")
    DeviceViews.Request approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/requests/{id}/reject")
    DeviceViews.Request reject(@PathVariable UUID id, @RequestBody(required = false) DeviceViews.Reason body) {
        return service.reject(id, body);
    }

    @PostMapping("/{personId}/reset")
    DeviceViews.Row reset(@PathVariable UUID personId, @RequestBody(required = false) DeviceViews.Reason body) {
        return service.reset(personId, body);
    }

    @PostMapping("/{personId}/block")
    DeviceViews.Row block(@PathVariable UUID personId, @RequestBody(required = false) DeviceViews.Reason body) {
        return service.block(personId, body);
    }

    @PostMapping("/{personId}/unblock")
    DeviceViews.Row unblock(@PathVariable UUID personId) {
        return service.unblock(personId);
    }

    @GetMapping("/history")
    DeviceViews.History history(@RequestParam(required = false) String kind, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.history(kind, page, size);
    }

    @GetMapping("/settings")
    DeviceViews.Settings settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    DeviceViews.Settings saveSettings(@RequestBody DeviceViews.Settings body) {
        return service.saveSettings(body);
    }
}
