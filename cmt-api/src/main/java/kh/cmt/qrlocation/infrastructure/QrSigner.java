package kh.cmt.qrlocation.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The content of a gate's QR code: {@code cmt://gate/{gateId}?v={version}&sig={sig}}. {@code sig} = the first 16 bytes
 * (base64url, 22 characters) of HMAC-SHA256 with the server key {@code cmt.qr.secret} over
 * {@code school|gate|version|salt}. The key never leaves the server and only a SHA-256 hash of {@code sig} is stored, so
 * neither a database copy nor someone guessing can make a valid QR; with the key and the stored salt the server makes the
 * same QR again to print it. <b>Changing the key makes every printed QR invalid</b> (print new ones).
 */
@Component
public class QrSigner {

    static final String SCHEME = "cmt";
    static final int MIN_SECRET = 32;
    private static final int SIG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    QrSigner(@Value("${cmt.qr.secret:}") String secret) {
        if (secret == null || secret.length() < MIN_SECRET) {
            throw new IllegalStateException("cmt.qr.secret (env QR_SECRET) must be set, at least " + MIN_SECRET + " characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** What the printed QR holds, and the hash to store. */
    public record Signed(String payload, String sig, String sigHash) {
    }

    /** What a scanned QR says (not yet checked against the stored version). */
    public record Parsed(UUID gateId, int version, String sig) {
    }

    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(salt);
    }

    public Signed sign(UUID schoolId, UUID gateId, int version, String salt) {
        String sig = sig(schoolId, gateId, version, salt);
        return new Signed(SCHEME + "://gate/" + gateId + "?v=" + version + "&sig=" + sig, sig, hash(sig));
    }

    /** True when {@code sig} is the one this server makes for that version and matches the stored hash. */
    public boolean matches(UUID schoolId, UUID gateId, int version, String salt, String storedHash, String sig) {
        byte[] expected = sig(schoolId, gateId, version, salt).getBytes(StandardCharsets.US_ASCII);
        byte[] given = sig.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given)
                && MessageDigest.isEqual(hash(sig).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reads {@code cmt://gate/{id}?v={n}&sig={s}}; empty for anything else (another app's QR, a typo, a URL). */
    public static Optional<Parsed> parse(String payload) {
        if (payload == null || payload.length() > 300) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(payload.trim());
            if (!SCHEME.equals(uri.getScheme()) || !"gate".equals(uri.getHost()) || uri.getPath() == null || uri.getRawQuery() == null) {
                return Optional.empty();
            }
            UUID gate = UUID.fromString(uri.getPath().replaceFirst("^/", ""));
            Integer version = null;
            String sig = null;
            for (String part : uri.getRawQuery().split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && kv[0].equals("v")) {
                    version = Integer.valueOf(kv[1]);
                } else if (kv.length == 2 && kv[0].equals("sig")) {
                    sig = kv[1];
                }
            }
            if (version == null || version < 1 || sig == null || !sig.matches("[A-Za-z0-9_-]{22}")) {
                return Optional.empty();
            }
            return Optional.of(new Parsed(gate, version, sig));
        } catch (Exception notOurs) {
            return Optional.empty();
        }
    }

    private String sig(UUID schoolId, UUID gateId, int version, String salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(("cmt-gate-qr|" + schoolId + "|" + gateId + "|" + version + "|" + salt).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, SIG_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    static String hash(String sig) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sig.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
