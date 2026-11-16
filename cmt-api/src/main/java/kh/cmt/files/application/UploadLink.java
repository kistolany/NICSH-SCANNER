package kh.cmt.files.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Where to upload: send the bytes with HTTP {@code method} (PUT) to {@code uploadUrl}, with the header
 * {@code Content-Type: contentType}, before {@code expiresAt}; then confirm {@code fileId}. At most {@code maxBytes}.
 */
public record UploadLink(UUID fileId, String uploadUrl, String method, String contentType, Instant expiresAt, long maxBytes) {
}
