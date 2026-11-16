package kh.cmt.files.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import kh.cmt.files.api.FilePurpose;

/** An uploaded file: {@code status} PENDING | READY; for images their size in pixels and whether a thumbnail exists. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileView(UUID id, FilePurpose purpose, String status, String name, String mime, long size, Integer width,
        Integer height, boolean hasThumbnail) {
}
