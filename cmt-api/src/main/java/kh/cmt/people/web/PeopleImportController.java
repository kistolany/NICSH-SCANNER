package kh.cmt.people.web;

import java.io.IOException;
import java.io.InputStream;
import kh.cmt.people.application.ImportResult;
import kh.cmt.people.application.PeopleImportService;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Admin Web, People › Import from Excel (three steps: Download template, Upload &amp; preview, Import).
 * <pre>
 * GET  /api/v1/admin/people/import/template   the .xlsx template
 * POST /api/v1/admin/people/import/preview    multipart "file": every row checked, nothing saved
 * POST /api/v1/admin/people/import/commit     multipart "file" (the same one): good rows saved, bad rows listed
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/people/import")
class PeopleImportController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PeopleImportService service;

    PeopleImportController(PeopleImportService service) {
        this.service = service;
    }

    @GetMapping("/template")
    ResponseEntity<StreamingResponseBody> template() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.template(out);   // written first, so a refusal is a normal error answer
        byte[] bytes = out.toByteArray();
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("people-import-template.xlsx").build().toString())
                .body(stream -> stream.write(bytes));
    }

    @PostMapping("/preview")
    ImportResult preview(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return service.preview(file.getOriginalFilename(), file.getSize(), in);
        }
    }

    @PostMapping("/commit")
    ImportResult commit(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FILE);
        }
        try (InputStream in = file.getInputStream()) {
            return service.commit(file.getOriginalFilename(), file.getSize(), in);
        }
    }
}
