package kh.cmt.attendance.application;

/** What the app sends. */
public final class ScanCommands {

    private ScanCommands() {
    }

    /**
     * POST /app/attendance/scan. {@code qrToken}: what the camera read; {@code lat} / {@code lng} / {@code accuracy}
     * (metres): the phone's position (missing = LOCATION_OFF); {@code clientTime}: when the phone scanned (ISO 8601, as
     * sent — part of the signed text); {@code isMockLocation} / {@code isRooted}: what the phone reports about itself;
     * {@code signature}: base64 signature of the linked phone's key (required once the phone has sent a key);
     * {@code idempotencyKey}: here or in the {@code Idempotency-Key} header.
     */
    public record Scan(String qrToken, Double lat, Double lng, Double accuracy, String clientTime, Boolean isMockLocation, Boolean isRooted,
            String signature, String idempotencyKey) {
    }

    /** POST /app/attendance/scan/early: the reason for leaving early, for the scan that asked for it ({@code ticket}). */
    public record Early(String ticket, String reason, String note) {
    }
}
