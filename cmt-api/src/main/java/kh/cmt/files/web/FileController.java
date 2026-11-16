package kh.cmt.files.web;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import kh.cmt.files.application.DownloadLink;
import kh.cmt.files.application.FileCommands;
import kh.cmt.files.application.FileService;
import kh.cmt.files.application.FileView;
import kh.cmt.files.application.UploadLink;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uploads and downloads, for the Admin Web and the app.
 * <pre>
 * POST /api/v1/files/upload-url        {purpose, fileName, contentType, size} → {fileId, uploadUrl, method, expiresAt}
 *      (then PUT the bytes to uploadUrl)
 * POST /api/v1/files/{id}/confirm      checks the upload; the file is READY to be attached to a record
 * GET  /api/v1/files/{id}/url?thumbnail=true|false   a short-lived link to read it
 * GET  /api/v1/files/{id}/view?thumbnail=true|false  the same, as a 302 redirect (for {@code <img src>})
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/files")
class FileController {

    private final FileService service;

    FileController(FileService service) {
        this.service = service;
    }

    @PostMapping("/upload-url")
    UploadLink uploadUrl(@RequestBody FileCommands.RequestUpload command) {
        return service.requestUpload(command);
    }

    @PostMapping("/{id}/confirm")
    FileView confirm(@PathVariable UUID id) {
        return service.confirm(id);
    }

    @GetMapping("/{id}/url")
    DownloadLink url(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean thumbnail) {
        return service.downloadLink(id, thumbnail);
    }

    /**
     * The same link as a redirect, so a page can show a picture with a plain {@code <img src>}. The browser may reuse
     * the redirect for a few minutes, shorter than the link it points to lives.
     */
    @GetMapping("/{id}/view")
    ResponseEntity<Void> view(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean thumbnail) {
        DownloadLink link = service.downloadLink(id, thumbnail);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.url()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(3)).cachePrivate()).build();
    }
}
