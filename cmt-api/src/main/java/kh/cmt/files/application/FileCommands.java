package kh.cmt.files.application;

/** What the apps send to the files endpoints. */
public final class FileCommands {

    private FileCommands() {
    }

    /**
     * Ask for an upload link: {@code purpose} PHOTO | DOCUMENT | LOGO, the file's name, its type (image/jpeg,
     * image/png, application/pdf) and its size in bytes. The real type and size are checked again at confirm.
     */
    public record RequestUpload(String purpose, String fileName, String contentType, Long size) {
    }
}
