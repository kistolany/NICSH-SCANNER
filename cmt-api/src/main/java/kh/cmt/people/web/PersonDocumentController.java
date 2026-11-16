package kh.cmt.people.web;

import java.util.List;
import java.util.UUID;
import kh.cmt.people.application.DocumentView;
import kh.cmt.people.application.PersonCommands;
import kh.cmt.people.application.PersonDocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, person profile › Documents.
 * <pre>
 * GET    /api/v1/admin/people/{id}/documents
 * POST   /api/v1/admin/people/{id}/documents        {type, fileId, issueDate, expiryDate, note} (file uploaded first)
 * DELETE /api/v1/admin/people/{id}/documents/{documentId}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/people/{id}/documents")
class PersonDocumentController {

    private final PersonDocumentService service;

    PersonDocumentController(PersonDocumentService service) {
        this.service = service;
    }

    @GetMapping
    List<DocumentView> list(@PathVariable UUID id) {
        return service.list(id);
    }

    @PostMapping
    ResponseEntity<DocumentView> add(@PathVariable UUID id, @RequestBody PersonCommands.AddDocument command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(id, command));
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID documentId) {
        service.delete(id, documentId);
        return ResponseEntity.noContent().build();
    }
}
