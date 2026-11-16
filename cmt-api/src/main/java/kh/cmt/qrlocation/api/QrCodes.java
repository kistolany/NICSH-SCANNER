package kh.cmt.qrlocation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Checks a scanned gate QR code (the scan, step 7.2; offline scans at the time they were made, 7.3). Answers for the
 * caller's school only.
 */
public interface QrCodes {

    /**
     * {@code VALID}; {@code QR_INVALID}: not a CMT gate QR, a forged / unknown one, another school's, or the gate was
     * moved to inactive; {@code QR_EXPIRED}: a real QR of the gate that was replaced or expired before {@code at} (a
     * replaced one is still accepted for 5 minutes).
     */
    Check verify(String payload, Instant at);

    enum Result { VALID, QR_INVALID, QR_EXPIRED }

    /** {@code gateId}, {@code areaId}, {@code version} are set when the QR was recognised (VALID or QR_EXPIRED). */
    record Check(Result result, UUID gateId, UUID areaId, Integer version, Instant expiresAt) {

        public boolean valid() {
            return result == Result.VALID;
        }
    }
}
