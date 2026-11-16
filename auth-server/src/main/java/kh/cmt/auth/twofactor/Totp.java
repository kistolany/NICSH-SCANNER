package kh.cmt.auth.twofactor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 (HMAC-SHA1, 30-second steps, 6 digits): the format every
 * authenticator app (Google Authenticator, Microsoft Authenticator, Authy...) understands.
 */
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new random 160-bit secret as Base32 text (what the user types or scans). */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static long stepAt(long epochSeconds) {
        return epochSeconds / PERIOD_SECONDS;
    }

    /** The 6-digit code for a 30-second step. */
    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % POWERS[DIGITS]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks a code against the steps just before, at and just after {@code nowStep} (clock drift), never
     * accepting a step at or below {@code lastUsedStep}. Returns the matched step, or -1 if none matches.
     */
    public static long verify(String base32Secret, String submitted, long nowStep, long lastUsedStep) {
        if (submitted == null || !submitted.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long matched = -1;
        for (long step = nowStep - 1; step <= nowStep + 1; step++) {
            boolean equal = MessageDigest.isEqual(code(base32Secret, step).getBytes(StandardCharsets.US_ASCII),
                    submitted.getBytes(StandardCharsets.US_ASCII));
            if (equal && step > lastUsedStep) {
                matched = step;   // keep looping so the time taken does not depend on which step matched
            }
        }
        return matched;
    }

    /** The link an authenticator app reads from the QR code. */
    public static String otpAuthUri(String issuer, String account, String base32Secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------------------------------------------------------------- Base32 (RFC 4648, no padding)

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] base32Decode(String text) {
        String clean = text.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 character: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }
}
