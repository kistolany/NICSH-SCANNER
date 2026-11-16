package kh.cmt.qrlocation.web;

import java.net.URI;
import java.util.UUID;
import kh.cmt.qrlocation.application.QrService;
import kh.cmt.qrlocation.application.QrViews;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, Locations &amp; QR: gate QR codes.
 * <pre>
 * POST /api/v1/admin/gates/{id}/qr                     new version; the open one expires in 5 minutes → {qr, replaced}
 * GET  /api/v1/admin/gates/{id}/qr/print.pdf?version=   the A4 sheet to post at the gate (the open version when left out)
 * GET  /api/v1/admin/gates/{id}/qr/print.png?version=   the same as an image
 * GET  /api/v1/admin/qr/history?gateId=&page=&size=     newest first
 * POST /api/v1/admin/qr/{id}/expire                     expire now
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin")
class QrController {

    private final QrService service;

    QrController(QrService service) {
        this.service = service;
    }

    @PostMapping("/gates/{id}/qr")
    ResponseEntity<QrViews.Created> create(@PathVariable UUID id) {
        QrViews.Created created = service.create(id);
        return ResponseEntity.created(URI.create("/api/v1/admin/gates/" + id + "/qr/print.pdf?version=" + created.qr().version()))
                .body(created);
    }

    @GetMapping("/gates/{id}/qr/print.pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "false") boolean download) {
        return file(service.sheet(id, version, "pdf"), download);
    }

    @GetMapping("/gates/{id}/qr/print.png")
    ResponseEntity<byte[]> png(@PathVariable UUID id, @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "true") boolean download) {
        return file(service.sheet(id, version, "png"), download);
    }

    @GetMapping("/qr/history")
    QrViews.History history(@RequestParam(required = false) UUID gateId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.history(gateId, page, size);
    }

    @PostMapping("/qr/{id}/expire")
    QrViews.Qr expire(@PathVariable UUID id) {
        return service.expire(id);
    }

    /** Opened in the browser (the PDF, to print) or saved ({@code download}); never cached: it is a key to check in. */
    private static ResponseEntity<byte[]> file(QrViews.Sheet sheet, boolean download) {
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(sheet.fileName()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(sheet.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(sheet.bytes());
    }
}
