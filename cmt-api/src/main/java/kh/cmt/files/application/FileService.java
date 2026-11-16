package kh.cmt.files.application;

import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.files.api.FilePurpose.FileTypes;
import kh.cmt.files.api.FileReadPolicy;
import kh.cmt.files.api.FileRef;
import kh.cmt.files.api.Files;
import kh.cmt.files.domain.StoredFile;
import kh.cmt.files.infrastructure.ObjectStore;
import kh.cmt.files.infrastructure.StoredFileRepository;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Uploads: the app asks for a link ({@link #requestUpload}), sends the bytes straight to object storage with it, then
 * confirms ({@link #confirm}). Only then is the file checked — the real size, the type from the content (not what the
 * app said), images decoded, scaled down when larger than 2000 px and given a thumbnail — and READY to be attached to
 * a record. Downloads go through short-lived links ({@link #downloadLink}); who may get one is decided by the module
 * whose record uses the file ({@link FileReadPolicy}); a file no record uses yet is only for its uploader.
 * <p>
 * Office users may upload for every purpose; app users only their photo and attachments to their own requests. Other
 * modules use {@link Files}.
 */
@Service
@Transactional
public class FileService implements Files {

    private final StoredFileRepository files;
    private final ObjectStore store;
    private final ObjectProvider<FileReadPolicy> policies;
    private final SchoolContext schoolContext;
    private final Clock clock;

    FileService(StoredFileRepository files, ObjectStore store, ObjectProvider<FileReadPolicy> policies,
            SchoolContext schoolContext, Clock clock) {
        this.files = files;
        this.store = store;
        this.policies = policies;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ upload

    /** Step 1: a file record (PENDING) and the link to upload it with HTTP PUT. */
    public UploadLink requestUpload(FileCommands.RequestUpload command) {
        CurrentUser caller = CurrentUser.require();
        FilePurpose purpose = purpose(command.purpose());
        if (caller.isApp() ? purpose != FilePurpose.PHOTO && purpose != FilePurpose.ATTACHMENT : !caller.isWeb()) {
            throw new ApiException(ErrorCode.FORBIDDEN);   // the app uploads the user's own photo and attachments to own requests
        }
        String mime = command.contentType() == null ? "" : command.contentType().trim().toLowerCase(Locale.ROOT);
        if (mime.equals("image/jpg")) {
            mime = FileTypes.JPEG;
        }
        if (!purpose.types().contains(mime)) {
            throw new ApiException(ErrorCode.FILE_TYPE).with("allowed", purpose.types().stream().sorted().toList());
        }
        if (command.size() == null || command.size() < 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", "size", "rule", "Positive")));
        }
        if (command.size() > purpose.maxBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE).with("maxBytes", purpose.maxBytes());
        }
        StoredFile file = new StoredFile();
        UUID school = schoolContext.currentSchoolId();
        String key = school + "/" + purpose.name().toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID() + extension(mime);
        file.setBucket(store.bucket());
        file.setObjectKey(key);
        file.setMime(mime);
        file.setSize(command.size());
        file.setPurpose(purpose);
        file.setOriginalName(fileName(command.fileName(), mime));
        file.setUploadedBy(caller.userId());
        file = files.saveAndFlush(file);
        Instant expires = clock.instant().plus(store.uploadLinkTtl());
        return new UploadLink(file.getId(), store.uploadLink(key), "PUT", mime, expires, purpose.maxBytes());
    }

    /**
     * Step 3: the upload is finished. The file is checked and becomes READY (repeating it changes nothing). Refused
     * when nothing was uploaded yet ({@code FILE_NOT_UPLOADED}), when it is too large or not of the type it claimed —
     * the upload is then thrown away and must be done again.
     */
    public FileView confirm(UUID id) {
        StoredFile file = ownPending(id);
        if (file.getStatus() == StoredFile.Status.READY) {
            return view(file);
        }
        OptionalLong stored = store.size(file.getObjectKey());
        if (stored.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_NOT_UPLOADED);
        }
        FilePurpose purpose = file.getPurpose();
        if (stored.getAsLong() > purpose.maxBytes()) {
            store.remove(file.getObjectKey());
            throw new ApiException(ErrorCode.FILE_TOO_LARGE).with("maxBytes", purpose.maxBytes());
        }
        byte[] bytes = store.read(file.getObjectKey());
        Optional<String> actual = FileContent.sniff(bytes);
        if (actual.isEmpty() || !actual.get().equals(file.getMime())) {
            store.remove(file.getObjectKey());
            throw new ApiException(ErrorCode.FILE_TYPE).with("allowed", List.of(file.getMime()));
        }
        Integer width = null;
        Integer height = null;
        String thumbnailKey = null;
        if (!FileTypes.PDF.equals(file.getMime())) {
            BufferedImage image = FileContent.decode(bytes).orElseThrow(() -> {
                store.remove(file.getObjectKey());
                return new ApiException(ErrorCode.FILE_INVALID);
            });
            if (Math.max(image.getWidth(), image.getHeight()) > FileContent.MAX_SIDE) {
                boolean png = FileTypes.PNG.equals(file.getMime());
                image = FileContent.fit(image, FileContent.MAX_SIDE, !png);
                bytes = FileContent.encode(image, png ? "png" : "jpg");
                store.write(file.getObjectKey(), bytes, file.getMime());
            }
            width = image.getWidth();
            height = image.getHeight();
            thumbnailKey = file.getObjectKey().replaceAll("\\.[a-z]+$", "") + "_thumb.jpg";
            store.write(thumbnailKey, FileContent.encode(FileContent.fit(image, FileContent.THUMB_SIDE, true), "jpg"), FileTypes.JPEG);
        }
        file.ready(bytes.length, FileContent.sha256(bytes), width, height, thumbnailKey);
        return view(files.saveAndFlush(file));
    }

    // ------------------------------------------------------------------ download

    /** A short-lived link to the file (or its thumbnail), if the caller may read it. Unknown or not allowed: 404. */
    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID id, boolean thumbnail) {
        CurrentUser caller = CurrentUser.require();
        StoredFile file = files.findById(id).filter(f -> f.getSchoolId().equals(caller.schoolId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!mayRead(file, caller)) {
            throw new ApiException(ErrorCode.NOT_FOUND);   // not "forbidden": do not tell that the file exists
        }
        if (file.getStatus() != StoredFile.Status.READY) {
            throw new ApiException(ErrorCode.FILE_NOT_READY);
        }
        if (thumbnail && file.getThumbnailKey() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);   // PDFs have none
        }
        String key = thumbnail ? file.getThumbnailKey() : file.getObjectKey();
        String mime = thumbnail ? FileTypes.JPEG : file.getMime();
        return new DownloadLink(store.downloadLink(key, mime, file.getOriginalName()), clock.instant().plus(store.downloadLinkTtl()),
                mime, file.getOriginalName());
    }

    private boolean mayRead(StoredFile file, CurrentUser caller) {
        for (FileReadPolicy policy : policies.orderedStream().toList()) {
            FileReadPolicy.Decision decision = policy.decide(file.getId(), caller);
            if (decision != FileReadPolicy.Decision.ABSTAIN) {
                return decision == FileReadPolicy.Decision.ALLOW;
            }
        }
        return caller.userId().equals(file.getUploadedBy());   // not used by any record yet: only who uploaded it
    }

    // ------------------------------------------------------------------ for other modules

    @Override
    public FileRef attach(UUID fileId, FilePurpose purpose) {
        StoredFile file = files.findById(fileId)
                .filter(f -> f.getSchoolId().equals(schoolContext.currentSchoolId()))
                .filter(f -> f.getStatus() == StoredFile.Status.READY && f.getPurpose() == purpose)
                .orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_READY));
        if (file.isAttached()) {
            throw new ApiException(ErrorCode.FILE_IN_USE);
        }
        file.setAttached(true);
        return ref(files.saveAndFlush(file));
    }

    @Override
    public void discard(UUID fileId) {
        files.findById(fileId).ifPresent(file -> {
            files.delete(file);
            files.flush();
            List<String> keys = file.getThumbnailKey() == null ? List.of(file.getObjectKey())
                    : List.of(file.getObjectKey(), file.getThumbnailKey());
            Runnable remove = () -> keys.forEach(store::remove);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        remove.run();
                    }
                });
            } else {
                remove.run();
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileRef> find(UUID fileId) {
        return fileId == null ? Optional.empty() : files.findById(fileId).map(FileService::ref);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> read(UUID fileId) {
        if (fileId == null) {
            return Optional.empty();
        }
        UUID school = schoolContext.currentSchoolId();
        return files.findById(fileId).filter(f -> f.getSchoolId().equals(school) && f.getStatus() == StoredFile.Status.READY)
                .map(f -> store.read(f.getObjectKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, FileRef> find(Collection<UUID> fileIds) {
        Map<UUID, FileRef> found = new HashMap<>();
        files.findAllById(fileIds).forEach(f -> found.put(f.getId(), ref(f)));
        return found;
    }

    // ------------------------------------------------------------------ helpers

    /** A file the caller uploaded (confirming is for the uploader only). */
    private StoredFile ownPending(UUID id) {
        CurrentUser caller = CurrentUser.require();
        return files.findById(id).filter(f -> f.getSchoolId().equals(caller.schoolId()) && caller.userId().equals(f.getUploadedBy()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static FilePurpose purpose(String value) {
        try {
            return FilePurpose.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", "purpose", "rule", "OneOf")));
        }
    }

    private static String extension(String mime) {
        return switch (mime) {
            case FileTypes.JPEG -> ".jpg";
            case FileTypes.PNG -> ".png";
            default -> ".pdf";
        };
    }

    /** The name to show: no folders, no control characters, at most 255 characters. */
    private static String fileName(String name, String mime) {
        String cleaned = name == null ? "" : name.replaceAll(".*[/\\\\]", "").replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.isEmpty()) {
            cleaned = "file" + extension(mime);
        }
        return cleaned.length() > 255 ? cleaned.substring(cleaned.length() - 255) : cleaned;
    }

    private static FileRef ref(StoredFile f) {
        return new FileRef(f.getId(), f.getPurpose(), f.getOriginalName(), f.getMime(), f.getSize(), f.getWidth(), f.getHeight(),
                f.getThumbnailKey() != null);
    }

    private static FileView view(StoredFile f) {
        return new FileView(f.getId(), f.getPurpose(), f.getStatus().name(), f.getOriginalName(), f.getMime(), f.getSize(),
                f.getWidth(), f.getHeight(), f.getThumbnailKey() != null);
    }
}
