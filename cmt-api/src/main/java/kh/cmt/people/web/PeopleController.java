package kh.cmt.people.web;

import java.net.URI;
import java.util.UUID;
import kh.cmt.people.application.LoginSent;
import kh.cmt.people.application.PersonCommands;
import kh.cmt.people.application.PersonService;
import kh.cmt.people.application.PersonSummary;
import kh.cmt.people.application.PersonView;
import kh.cmt.shared.web.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Web, People page.
 * <pre>
 * GET    /api/v1/admin/people?q=&amp;dept=&amp;role=&amp;status=active|inactive&amp;sort=&amp;page=&amp;size=
 * GET    /api/v1/admin/people/{id}
 * POST   /api/v1/admin/people                    Add person (201; Staff ID handed out)
 * PUT    /api/v1/admin/people/{id}               Edit person (send the version you read)
 * POST   /api/v1/admin/people/{id}/inactivate    {reason, lastWorkingDay}: move to inactive, login ends
 * POST   /api/v1/admin/people/{id}/restore       {allowLogin}
 * POST   /api/v1/admin/people/{id}/send-login    {username?, password?, mustChangePassword?}: create the login, or a new password
 * POST   /api/v1/admin/people/{id}/reset-password  {password?, mustChangePassword?}: new password (empty = generated,
 *                                                  shown once), signed out everywhere; nothing is emailed
 * DELETE /api/v1/admin/people/{id}               delete forever (Super Admin, inactive, no history, no login)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/people")
class PeopleController {

    private final PersonService service;

    PeopleController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<PersonSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) UUID dept,
            @RequestParam(required = false) String role, @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(q, dept, role, status, sort, page, size);
    }

    @GetMapping("/{id}")
    PersonView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<PersonView> create(@RequestBody PersonCommands.Save command) {
        PersonView created = service.create(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/people/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    PersonView update(@PathVariable UUID id, @RequestBody PersonCommands.Save command) {
        return service.update(id, command);
    }

    @PostMapping("/{id}/inactivate")
    ResponseEntity<Void> inactivate(@PathVariable UUID id, @RequestBody(required = false) PersonCommands.Inactivate command) {
        service.inactivate(id, command);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    PersonView restore(@PathVariable UUID id, @RequestBody(required = false) PersonCommands.Restore command) {
        return service.restore(id, command);
    }

    @PostMapping("/{id}/send-login")
    LoginSent sendLogin(@PathVariable UUID id, @RequestBody(required = false) PersonCommands.SendLogin command) {
        return service.sendLogin(id, command);
    }

    @PostMapping("/{id}/reset-password")
    LoginSent resetPassword(@PathVariable UUID id, @RequestBody(required = false) PersonCommands.ResetPassword command) {
        return service.resetPassword(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteForever(@PathVariable UUID id) {
        service.deleteForever(id);
        return ResponseEntity.noContent().build();
    }
}
