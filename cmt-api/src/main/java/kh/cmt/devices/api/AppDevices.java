package kh.cmt.devices.api;

import java.util.UUID;

/**
 * Is this request coming from the phone the account is linked to, and what does the school allow (the scan, step
 * 7.2)? Read fresh on every call: a reset, block or new minimum version applies at once.
 */
public interface AppDevices {

    /**
     * {@code tokenDeviceId}: the {@code device_id} claim of the access token; {@code appVersion}: the
     * {@code X-App-Version} header (null = not checked).
     */
    Check check(UUID accountId, UUID tokenDeviceId, String appVersion);

    /** OK, or why the phone may not be used: DEVICE_MISMATCH, DEVICE_BLOCKED, UPDATE_REQUIRED (with {@code minVersion}). */
    enum Result { OK, DEVICE_MISMATCH, DEVICE_BLOCKED, UPDATE_REQUIRED }

    /**
     * {@code publicKey}: the linked phone's key (base64 X.509 SubjectPublicKeyInfo, EC P-256 or RSA), null while the
     * app does not send one; {@code blockRooted} / {@code blockMockLocation}: the school's device settings.
     */
    record Check(Result result, UUID deviceId, String minVersion, String publicKey, boolean blockRooted, boolean blockMockLocation) {
    }
}
