package kh.cmt.iam.web;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import kh.cmt.iam.application.WebUserCommands;
import kh.cmt.iam.application.WebUserService;
import kh.cmt.iam.application.WebUserView;
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
 * Admin Web, Web users and roles page, Web users tab.
 * <pre>
 * GET    /api/v1/admin/web-users?status=active|inactive&amp;q=&amp;role=&amp;page=&amp;size=   list (by name)
 * GET    /api/v1/admin/web-users/{id}
 * POST   /api/v1/admin/web-users                          new web user {…, password?, mustChangePassword?} → + temporaryPassword
 * PUT    /api/v1/admin/web-users/{id}                     save the form (send the version you read)
 * DELETE /api/v1/admin/web-users/{id}?reason=             deactivate (signed out everywhere at once)
 * POST   /api/v1/admin/web-users/{id}/restore             make active again
 * POST   /api/v1/admin/web-users/{id}/reset-password      {password?, mustChangePassword?} → {username, temporaryPassword?, mustChangePassword}
 * POST   /api/v1/admin/web-users/{id}/sign-out            sign out everywhere
 * POST   /api/v1/admin/web-users/{id}/reset-two-factor    remove the authenticator (lost phone)
 * POST   /api/v1/admin/web-users/move-role                {userIds, role}: move users to another role
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/web-users")
class WebUserController {

    private final WebUserService service;

    WebUserController(WebUserService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<WebUserView> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) String role, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(status, q, role, page, size);
    }

    @GetMapping("/{id}")
    WebUserView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<WebUserService.Created> create(@RequestBody WebUserCommands.Create command) {
        WebUserService.Created created = service.create(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/web-users/" + created.user().id())).body(created);
    }

    @PutMapping("/{id}")
    WebUserView update(@PathVariable UUID id, @RequestBody WebUserCommands.Update command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deactivate(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        service.deactivate(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    WebUserView restore(@PathVariable UUID id) {
        return service.restore(id);
    }

    @PostMapping("/{id}/reset-password")
    WebUserService.PasswordGiven resetPassword(@PathVariable UUID id, @RequestBody(required = false) WebUserCommands.SetPassword command) {
        return service.resetPassword(id, command);
    }

    @PostMapping("/{id}/sign-out")
    Map<String, Integer> signOut(@PathVariable UUID id) {
        return Map.of("sessions", service.signOut(id));
    }

    @PostMapping("/{id}/reset-two-factor")
    WebUserView resetTwoFactor(@PathVariable UUID id) {
        return service.resetTwoFactor(id);
    }

    @PostMapping("/move-role")
    Map<String, Integer> moveRole(@RequestBody WebUserCommands.MoveRole command) {
        return Map.of("moved", service.moveRole(command));
    }
}
