package kh.cmt.files.api;

import java.util.UUID;

/**
 * What another module may show about a file: name, type, size, image size, whether it has a thumbnail. The bytes are
 * reached with {@code GET /api/v1/files/{id}/url} (a short-lived link), never through the record.
 */
public record FileRef(UUID id, FilePurpose purpose, String name, String mime, long size, Integer width, Integer height,
        boolean hasThumbnail) {
}
