package kh.cmt.devices.web;

import kh.cmt.devices.application.DeviceService;
import kh.cmt.devices.application.DeviceViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: the linked phone.
 * <pre>
 * GET  /api/v1/app/me/device        Profile › My device: name, platform, linked since, last used (404: none linked)
 * POST /api/v1/app/device/register  {deviceName, platform, osVersion, appVersion, publicKey, rooted}: the linked phone
 *                                   says what it is now (DEVICE_MISMATCH from another phone, DEVICE_BLOCKED if blocked)
 * </pre>
 * Linking, refusing another phone and asking for a change happen at the auth server ({@code /auth/login},
 * {@code /auth/device/change-request}).
 */
@RestController
class MyDeviceController {

    private final DeviceService service;

    MyDeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/app/me/device")
    DeviceViews.MyDevice myDevice() {
        return service.myDevice();
    }

    @PostMapping("/api/v1/app/device/register")
    DeviceViews.MyDevice register(@RequestBody DeviceViews.Register body) {
        return service.register(body);
    }
}
