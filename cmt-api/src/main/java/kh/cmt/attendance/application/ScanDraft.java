package kh.cmt.attendance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.UUID;
import kh.cmt.attendance.domain.ScanEvent;
import kh.cmt.devices.api.AppDevices;
import kh.cmt.qrlocation.api.QrCodes;
import kh.cmt.shared.security.CurrentUser;
import lombok.Getter;
import lombok.Setter;

/**
 * A scan while it is checked: what the phone sent, what the {@link ScanCheck}s and the service found out, and so what
 * is stored as its scan event whatever the outcome ({@link #facts}).
 */
@Getter
final class ScanDraft {

    // what came in
    private final ScanCommands.Scan command;
    private final String appVersion;
    private final UUID person;
    private final UUID account;
    private final UUID device;
    private final String key;
    private final Instant now;
    private final Instant clientTime;
    private final String qrToken;
    private final String qrHash;

    // what was found out
    @Setter private AppDevices.Check phone;
    @Setter private QrCodes.Check qr;
    @Setter private Boolean signatureOk;
    @Setter private UUID area;
    @Setter private Double distance;
    @Setter private ScanEvent.Kind kind;
    @Setter private UUID recordId;
    @Setter private Integer shiftIdx;

    ScanDraft(CurrentUser user, UUID person, String key, ScanCommands.Scan command, String appVersion, Instant now) {
        this.command = command;
        this.appVersion = appVersion;
        this.person = person;
        this.account = user.userId();
        this.device = user.deviceId();
        this.key = key;
        this.now = now;
        this.clientTime = clientTime(command.clientTime(), now);
        this.qrToken = command.qrToken().trim();
        this.qrHash = sha256(qrToken);
    }

    /** The scan event to store with this {@code result} (ACCEPTED or the refusal code). */
    ScanEvent.Facts facts(String result) {
        return new ScanEvent.Facts(person, account, device, key, ScanEvent.Source.ONLINE, clientTime, now, qrHash,
                qr == null ? null : qr.gateId(), qr == null ? null : qr.version(), decimal(command.lat(), 6), decimal(command.lng(), 6),
                decimal(command.accuracy(), 1), decimal(distance, 1), area, command.isMockLocation(), command.isRooted(), signatureOk, kind,
                result, recordId, shiftIdx, null);
    }

    UUID gate() {
        return qr == null ? null : qr.gateId();
    }

    /** The phone's own time, kept for the record only (the server's time counts); unreadable → the server's. */
    private static Instant clientTime(String sent, Instant fallback) {
        if (sent == null || sent.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(sent.trim()).toInstant();
        } catch (DateTimeParseException notOffset) {
            try {
                return Instant.parse(sent.trim());
            } catch (DateTimeParseException unreadable) {
                return fallback;
            }
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal decimal(Double d, int scale) {
        return d == null || d.isNaN() || d.isInfinite() ? null : BigDecimal.valueOf(d).setScale(scale, RoundingMode.HALF_UP);
    }
}
