package kh.cmt.attendance.application;

import java.util.Map;
import java.util.Optional;
import kh.cmt.shared.web.ErrorCode;

/**
 * One check a scan must pass before it counts (step 7.2), run in order by {@link ScanChecks}: empty = passed (the check
 * may note what it found on the draft for the next ones and for the stored scan event), else the refusal.
 */
@FunctionalInterface
interface ScanCheck {

    Optional<ScanViews.Refused> check(ScanDraft scan);

    Optional<ScanViews.Refused> PASSED = Optional.empty();

    static Optional<ScanViews.Refused> refuse(ErrorCode code) {
        return refuse(code, Map.of());
    }

    static Optional<ScanViews.Refused> refuse(ErrorCode code, Map<String, Object> details) {
        return Optional.of(new ScanViews.Refused(code, details));
    }
}
