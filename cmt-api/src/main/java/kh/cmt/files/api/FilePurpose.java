package kh.cmt.files.api;

import java.util.Set;

/**
 * What a file is uploaded for. It decides the types and the size allowed: a person's photo and the school logo are
 * JPG or PNG up to 2 MB (as the Add person form says); documents, and attachments people add to their own requests
 * from the staff app (a correction, a leave request), are JPG, PNG or PDF up to 5 MB.
 */
public enum FilePurpose {

    PHOTO(2 * 1024 * 1024, Set.of(FileTypes.JPEG, FileTypes.PNG)),
    LOGO(2 * 1024 * 1024, Set.of(FileTypes.JPEG, FileTypes.PNG)),
    DOCUMENT(5 * 1024 * 1024, Set.of(FileTypes.JPEG, FileTypes.PNG, FileTypes.PDF)),
    ATTACHMENT(5 * 1024 * 1024, Set.of(FileTypes.JPEG, FileTypes.PNG, FileTypes.PDF));

    private final long maxBytes;
    private final Set<String> types;

    FilePurpose(long maxBytes, Set<String> types) {
        this.maxBytes = maxBytes;
        this.types = types;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** The allowed content types (image/jpeg, image/png, application/pdf). */
    public Set<String> types() {
        return types;
    }

    /** The content types the files module knows. */
    public static final class FileTypes {
        public static final String JPEG = "image/jpeg";
        public static final String PNG = "image/png";
        public static final String PDF = "application/pdf";

        private FileTypes() {
        }
    }
}
