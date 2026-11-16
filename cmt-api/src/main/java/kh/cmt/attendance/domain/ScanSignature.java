package kh.cmt.attendance.domain;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * Signed scans: the linked phone signs each scan with the key it made in its secure storage (Android Keystore / iOS
 * Secure Enclave) and sent at login. The signed text is
 * {@code idempotencyKey|qrToken|clientTime|lat|lng} (lat / lng with 6 decimals, clientTime exactly as sent). Key:
 * base64 X.509 SubjectPublicKeyInfo, EC (P-256, SHA256withECDSA) or RSA (SHA256withRSA); signature: base64 (DER for EC).
 * A stolen access token used from another device cannot sign.
 */
public final class ScanSignature {

    private ScanSignature() {
    }

    public static String text(String idempotencyKey, String qrToken, String clientTime, Double lat, Double lng) {
        return String.join("|", nz(idempotencyKey), nz(qrToken), nz(clientTime), coordinate(lat), coordinate(lng));
    }

    /** True when {@code signature} is the phone key's signature of {@code text}; false for anything unreadable. */
    public static boolean verify(String publicKey, String text, String signature) {
        if (publicKey == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(publicKey.replaceAll("\\s", ""));
            PublicKey key;
            String algorithm;
            try {
                key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
                algorithm = "SHA256withECDSA";
            } catch (java.security.spec.InvalidKeySpecException notEc) {
                key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
                algorithm = "SHA256withRSA";
            }
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(text.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (Exception unreadable) {
            return false;
        }
    }

    private static String coordinate(Double d) {
        return d == null ? "" : String.format(Locale.ROOT, "%.6f", d);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
