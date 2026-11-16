package kh.cmt.attendance.web;

import kh.cmt.attendance.application.ScanCommands;
import kh.cmt.attendance.application.ScanService;
import kh.cmt.attendance.application.ScanViews;
import kh.cmt.shared.web.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: the scan.
 * <pre>
 * POST /api/v1/app/attendance/scan        {qrToken, lat, lng, accuracy, clientTime, isMockLocation, isRooted, signature}
 *                                         + header Idempotency-Key (or body idempotencyKey), X-App-Version
 *      200 {result CHECKED_IN|CHECKED_OUT, time, shiftIdx, shiftName, twoShifts, status ON_TIME|LATE|EARLY, lateMinutes,
 *           earlyMinutes, workedMinutes, workedTodayMinutes, shiftStart, shiftEnd, nextShift, gate, recordId}
 *      refused (problem JSON with code): QR_INVALID, QR_EXPIRED, OUTSIDE_AREA {distanceKm, radiusKm, areaName},
 *           LOCATION_OFF, MOCK_LOCATION, ROOTED_DEVICE, NO_SHIFT_TODAY {kind}, TOO_EARLY {shiftIdx, opensAt, shiftStart},
 *           ALREADY_CHECKED_IN {at, shiftIdx},
 *           ALREADY_CHECKED_OUT {at, shiftIdx}, EARLY_REASON_REQUIRED {ticket, shiftIdx, shiftEnd, now, earlyMinutes},
 *           DEVICE_MISMATCH, DEVICE_BLOCKED, UPDATE_REQUIRED {minVersion}, SIGNATURE_INVALID
 * POST /api/v1/app/attendance/scan/early  {ticket, reason, note} → 200 the check-out
 * </pre>
 * Refused scans are stored before the answer goes out (the service returns them; they are turned into errors here).
 */
@RestController
@RequestMapping("/api/v1/app/attendance/scan")
class ScanController {

    private final ScanService service;

    ScanController(ScanService service) {
        this.service = service;
    }

    @PostMapping
    ScanViews.Result scan(@RequestBody ScanCommands.Scan body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-App-Version", required = false) String appVersion) {
        return answer(service.scan(body, key, appVersion));
    }

    @PostMapping("/early")
    ScanViews.Result early(@RequestBody ScanCommands.Early body) {
        return answer(service.early(body));
    }

    private static ScanViews.Result answer(ScanViews.Outcome outcome) {
        return switch (outcome) {
            case ScanViews.Done done -> done.result();
            case ScanViews.Refused refused -> {
                ApiException e = new ApiException(refused.code());
                refused.details().forEach(e::with);
                throw e;
            }
        };
    }
}
