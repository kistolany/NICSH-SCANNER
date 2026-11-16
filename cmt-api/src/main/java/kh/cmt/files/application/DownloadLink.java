package kh.cmt.files.application;

import java.time.Instant;

/** A link to read a file, valid until {@code expiresAt} (a few minutes): ask again for a fresh one. */
public record DownloadLink(String url, Instant expiresAt, String mime, String name) {
}
