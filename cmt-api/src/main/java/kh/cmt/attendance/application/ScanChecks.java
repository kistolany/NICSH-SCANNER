package kh.cmt.attendance.application;

import static kh.cmt.attendance.application.ScanCheck.PASSED;
import static kh.cmt.attendance.application.ScanCheck.refuse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kh.cmt.attendance.domain.ScanSignature;
import kh.cmt.devices.api.AppDevices;
import kh.cmt.qrlocation.api.Locations;
import kh.cmt.qrlocation.api.QrCodes;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What a scan must pass before the day is looked at, in this order: <b>the phone</b> (linked, not blocked, app
 * version; signed once it has a key; rooted per the school's settings) → <b>the QR</b> (valid, or within the 5 minutes
 * after it was replaced) → <b>the place</b> (a position, not fake per the school's settings, inside the area of that
 * gate with the phone's accuracy up to {@value #MAX_TOLERANCE_M} m counted in its favour).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ScanChecks {

    static final double MAX_TOLERANCE_M = 50;

    private final AppDevices devices;
    private final QrCodes qrCodes;
    private final Locations locations;

    List<ScanCheck> inOrder() {
        return List.of(this::phone, this::signature, this::rooted, this::qr, this::locationOn, this::realLocation, this::insideArea);
    }

    private Optional<ScanViews.Refused> phone(ScanDraft s) {
        AppDevices.Check phone = devices.check(s.getAccount(), s.getDevice(), s.getAppVersion());
        s.setPhone(phone);
        return switch (phone.result()) {
            case DEVICE_MISMATCH -> refuse(ErrorCode.DEVICE_MISMATCH);
            case DEVICE_BLOCKED -> refuse(ErrorCode.DEVICE_BLOCKED);
            case UPDATE_REQUIRED -> refuse(ErrorCode.UPDATE_REQUIRED, Map.of("minVersion", phone.minVersion()));
            case OK -> PASSED;
        };
    }

    private Optional<ScanViews.Refused> signature(ScanDraft s) {
        String key = s.getPhone().publicKey();
        if (key == null) {
            return PASSED;   // the phone has not sent a key yet: nothing to check against
        }
        ScanCommands.Scan c = s.getCommand();
        boolean ok = ScanSignature.verify(key, ScanSignature.text(s.getKey(), c.qrToken(), c.clientTime(), c.lat(), c.lng()), c.signature());
        s.setSignatureOk(ok);
        return ok ? PASSED : refuse(ErrorCode.SIGNATURE_INVALID);
    }

    private Optional<ScanViews.Refused> rooted(ScanDraft s) {
        return s.getPhone().blockRooted() && Boolean.TRUE.equals(s.getCommand().isRooted()) ? refuse(ErrorCode.ROOTED_DEVICE) : PASSED;
    }

    private Optional<ScanViews.Refused> qr(ScanDraft s) {
        QrCodes.Check qr = qrCodes.verify(s.getQrToken(), s.getNow());
        s.setQr(qr);
        return switch (qr.result()) {
            case QR_INVALID -> refuse(ErrorCode.QR_INVALID);
            case QR_EXPIRED -> refuse(ErrorCode.QR_EXPIRED);
            case VALID -> PASSED;
        };
    }

    private Optional<ScanViews.Refused> locationOn(ScanDraft s) {
        return s.getCommand().lat() == null || s.getCommand().lng() == null ? refuse(ErrorCode.LOCATION_OFF) : PASSED;
    }

    private Optional<ScanViews.Refused> realLocation(ScanDraft s) {
        return s.getPhone().blockMockLocation() && Boolean.TRUE.equals(s.getCommand().isMockLocation()) ? refuse(ErrorCode.MOCK_LOCATION) : PASSED;
    }

    private Optional<ScanViews.Refused> insideArea(ScanDraft s) {
        ScanCommands.Scan c = s.getCommand();
        Optional<Locations.AreaHit> hit = locations.distanceTo(s.getQr().areaId(), c.lat(), c.lng());
        if (hit.isEmpty()) {
            return refuse(ErrorCode.QR_INVALID);   // the gate's area is gone
        }
        Locations.AreaHit area = hit.get();
        s.setArea(area.areaId());
        s.setDistance(area.metersOutside());
        double tolerance = Math.min(MAX_TOLERANCE_M, Math.max(0, c.accuracy() == null ? 0 : c.accuracy()));
        if (area.distanceMeters() <= area.radiusMeters() + tolerance) {
            return PASSED;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("distanceKm", Math.max(0.1, round(area.metersOutside() / 1000, 1)));
        details.put("radiusKm", round(area.radiusMeters() / 1000.0, 2));
        details.put("areaName", area.name());
        return refuse(ErrorCode.OUTSIDE_AREA, details);
    }

    private static double round(double v, int places) {
        return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
