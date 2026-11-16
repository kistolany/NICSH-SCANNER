/**
 * What other modules may use from the files module: {@link kh.cmt.files.api.Files} (attach / discard uploaded files),
 * {@link kh.cmt.files.api.FileReadPolicy} (who may download the files their records use), the purposes and the file
 * reference. The upload itself goes through {@code /api/v1/files} directly from the apps to object storage.
 */
@NamedInterface("api")
package kh.cmt.files.api;

import org.springframework.modulith.NamedInterface;
