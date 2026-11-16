package kh.cmt.iam.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.iam.application.RoleCommands;
import kh.cmt.iam.application.RoleComparison;
import kh.cmt.iam.application.RolePreview;
import kh.cmt.iam.application.RoleService;
import kh.cmt.iam.application.RoleVersionView;
import kh.cmt.iam.application.RoleView;
import kh.cmt.iam.application.WebUserService;
import kh.cmt.iam.application.WebUserView;
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
 * Admin Web, Web users and roles page, Roles and permissions tab.
 * <pre>
 * GET    /api/v1/admin/roles                      all active roles with their permission table
 * GET    /api/v1/admin/roles/{id}
 * POST   /api/v1/admin/roles                      new custom role (name, description, modules it may open)
 * PUT    /api/v1/admin/roles/{id}                 rename (custom roles; send the version you read)
 * PUT    /api/v1/admin/roles/{id}/permissions     save the permission table (custom roles; send the version)
 * PUT    /api/v1/admin/roles/{id}/scope           save the record scope (custom roles; send the version)
 * PUT    /api/v1/admin/roles/{id}/sensitive-fields  save what the role sees of personal data (custom roles; version)
 * PUT    /api/v1/admin/roles/{id}/approval-limits   save leave limit, old corrections, own requests, escalation (custom roles)
 * PUT    /api/v1/admin/roles/{id}/login-rules       save session timeout, allowed hours / days / IPs (custom roles)
 * PUT    /api/v1/admin/roles/{id}/two-factor        require two-step verification (any role; Security tab)
 * DELETE /api/v1/admin/roles/{id}                 delete a custom role (IN_USE while web users have it)
 * GET    /api/v1/admin/roles/compare?a=&amp;b=        two roles side by side
 * GET    /api/v1/admin/roles/{id}/users           users of this role
 * POST   /api/v1/admin/roles/{id}/force-logout    sign out every user of the role (not yourself)
 * GET    /api/v1/admin/roles/{id}/history         change history, newest first
 * POST   /api/v1/admin/roles/{id}/history/{v}/restore   bring back version v (custom roles)
 * GET    /api/v1/admin/roles/{id}/view-as         what a user of the role may do and see (read-only)
 * PUT    /api/v1/admin/roles/{id}/default         default role for new web users
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
class RoleController {

    private final RoleService service;
    private final WebUserService webUsers;

    RoleController(RoleService service, WebUserService webUsers) {
        this.service = service;
        this.webUsers = webUsers;
    }

    @GetMapping("/{id}/users")
    List<WebUserView> users(@PathVariable UUID id) {
        return webUsers.usersOfRole(id);
    }

    @PostMapping("/{id}/force-logout")
    Map<String, Integer> forceLogout(@PathVariable UUID id) {
        return Map.of("signedOut", webUsers.signOutRole(id));
    }

    @GetMapping("/{id}/history")
    List<RoleVersionView> history(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping("/{id}/history/{version}/restore")
    RoleView restoreVersion(@PathVariable UUID id, @PathVariable int version) {
        return service.restoreVersion(id, version);
    }

    @GetMapping("/{id}/view-as")
    RolePreview viewAs(@PathVariable UUID id) {
        return service.viewAs(id);
    }

    @PutMapping("/{id}/default")
    RoleView makeDefault(@PathVariable UUID id) {
        return service.makeDefault(id);
    }

    @GetMapping
    List<RoleView> list() {
        return service.list();
    }

    @GetMapping("/compare")
    RoleComparison compare(@RequestParam UUID a, @RequestParam UUID b) {
        return service.compare(a, b);
    }

    @GetMapping("/{id}")
    RoleView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<RoleView> create(@Valid @RequestBody RoleCommands.Create command) {
        RoleView created = service.create(command);
        return ResponseEntity.created(URI.create("/api/v1/admin/roles/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    RoleView update(@PathVariable UUID id, @Valid @RequestBody RoleCommands.Update command) {
        return service.update(id, command);
    }

    @PutMapping("/{id}/permissions")
    RoleView savePermissions(@PathVariable UUID id, @Valid @RequestBody RoleCommands.Permissions command) {
        return service.savePermissions(id, command);
    }

    @PutMapping("/{id}/scope")
    RoleView saveScope(@PathVariable UUID id, @RequestBody RoleCommands.Scope command) {
        return service.saveScope(id, command);
    }

    @PutMapping("/{id}/sensitive-fields")
    RoleView saveSensitiveFields(@PathVariable UUID id, @RequestBody RoleCommands.SensitiveFields command) {
        return service.saveSensitiveFields(id, command);
    }

    @PutMapping("/{id}/approval-limits")
    RoleView saveApprovalLimits(@PathVariable UUID id, @RequestBody RoleCommands.Limits command) {
        return service.saveApprovalLimits(id, command);
    }

    @PutMapping("/{id}/login-rules")
    RoleView saveLoginRules(@PathVariable UUID id, @RequestBody RoleCommands.Login command) {
        return service.saveLoginRules(id, command);
    }

    @PutMapping("/{id}/two-factor")
    RoleView saveTwoFactor(@PathVariable UUID id, @RequestBody RoleCommands.TwoFactor command) {
        return service.saveTwoFactor(id, command);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
