package kh.cmt.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Test helper: the SHA-256 hex the auth server stores instead of the refresh token. */
final class RefreshTokenHash {

    private RefreshTokenHash() {
    }

    static String of(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
