package kh.cmt.files.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules do with uploaded files. A record (a person's photo, a document, the school logo) takes a file
 * that was uploaded and confirmed: {@link #attach} checks it and marks it used, so it cannot be taken by another record.
 * When the record lets go of it, {@link #discard} removes it (after the transaction commits).
 */
public interface Files {

    /**
     * Takes a confirmed file of the caller's school for a record. Refused with {@code FILE_NOT_READY} (unknown, not
     * confirmed, another school, or uploaded for another purpose) or {@code FILE_IN_USE} (already attached).
     */
    FileRef attach(UUID fileId, FilePurpose purpose);

    /** The record no longer uses the file: it is deleted, with its bytes, once the transaction commits. */
    void discard(UUID fileId);

    Optional<FileRef> find(UUID fileId);

    /**
     * The bytes of a confirmed file of the caller's school, for files the server itself puts into something it makes
     * (e.g. the school logo on a printed QR sheet). Empty when unknown, not confirmed or of another school. Not for
     * sending files to people: they get short-lived links.
     */
    Optional<byte[]> read(UUID fileId);

    Map<UUID, FileRef> find(Collection<UUID> fileIds);
}
