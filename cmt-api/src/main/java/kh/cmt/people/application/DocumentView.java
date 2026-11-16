package kh.cmt.people.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.files.api.FileRef;

/**
 * A document on the person's profile.
 * <ul>
 *   <li>{@code type} NATIONAL_ID_FRONT | NATIONAL_ID_BACK | CONTRACT | DEGREE | CV | HEALTH_CERT | POLICE_CLEARANCE | OTHER</li>
 *   <li>{@code status} VALID | EXPIRING (within {@value PersonDocumentService#EXPIRING_DAYS} days) | EXPIRED (the expiry
 *       date has passed); {@code expired} for a quick flag; no expiry date = VALID</li>
 *   <li>{@code locked}: the caller's role may not see this kind of document (national ID scans, or national ID &amp;
 *       documents): only the type and the expiry are shown, no file and no note</li>
 *   <li>{@code file}: name, type, size; open it with {@code GET /api/v1/files/{file.id}/url}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentView(UUID id, String type, LocalDate issueDate, LocalDate expiryDate, String status, boolean expired,
        Integer expiresInDays, String note, FileRef file, boolean locked, Instant createdAt, UUID createdBy) {
}
