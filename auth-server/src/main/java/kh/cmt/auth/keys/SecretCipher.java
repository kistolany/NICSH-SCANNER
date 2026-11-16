package kh.cmt.auth.keys;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kh.cmt.auth.AuthProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts small secrets before they go into the database (signing-key private halves, authenticator
 * secrets) with AES-256-GCM. The key is derived from {@code auth.keys.encryption-secret} (AUTH_KEY_SECRET).
 * Output is Base64 of a random 12-byte IV followed by the ciphertext.
 */
@Component
public class SecretCipher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;

    public SecretCipher(AuthProperties props) {
        String secret = props.keys().encryptionSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("auth.keys.encryption-secret (AUTH_KEY_SECRET) must be set");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.aesKey = new SecretKeySpec(hash, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, all, 0, 12));
            return cipher.doFinal(all, 12, all.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong AUTH_KEY_SECRET?)", e);
        }
    }
}
