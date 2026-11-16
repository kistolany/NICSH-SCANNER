package kh.cmt.org.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.application.OrgItemCommand;
import kh.cmt.org.application.OrgItemService;
import kh.cmt.org.application.OrgItemView;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
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
 * Admin Web: Departments &amp; positions page, and the lists behind the other pages' dropdowns.
 * <pre>
 * GET    /api/v1/admin/{kind}?status=active|inactive&amp;q=&amp;page=&amp;size=
 * GET    /api/v1/admin/{kind}/{id}
 * POST   /api/v1/admin/{kind}                  create
 * PUT    /api/v1/admin/{kind}/{id}             change (send the version you saw)
 * DELETE /api/v1/admin/{kind}/{id}?reason=     move to inactive
 * POST   /api/v1/admin/{kind}/{id}/restore     back to active
 * DELETE /api/v1/admin/{kind}/{id}/forever     remove for good (Super Admin, inactive items only)
 * </pre>
 * {kind} is departments or positions.
 */
@RestController
@RequestMapping("/api/v1/admin")
class OrgController {

    private static final String KIND = "/{kind:departments|positions}";

    private final OrgItemService service;

    OrgController(OrgItemService service) {
        this.service = service;
    }

    @GetMapping(KIND)
    PageResponse<OrgItemView> list(@PathVariable String kind, @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        boolean inactive = switch (status) {
            case "active" -> false;
            case "inactive" -> true;
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", java.util.List.of(java.util.Map.of("field", "status", "rule", "Pattern")));
        };
        return service.list(kindOf(kind), inactive, q, page, size);
    }

    @GetMapping(KIND + "/{id}")
    OrgItemView get(@PathVariable String kind, @PathVariable UUID id) {
        return service.get(kindOf(kind), id);
    }

    @PostMapping(KIND)
    ResponseEntity<OrgItemView> create(@PathVariable String kind, @Valid @RequestBody OrgItemCommand command) {
        OrgItemView created = service.create(kindOf(kind), command);
        return ResponseEntity.created(URI.create("/api/v1/admin/" + kind + "/" + created.id())).body(created);
    }

    @PutMapping(KIND + "/{id}")
    OrgItemView update(@PathVariable String kind, @PathVariable UUID id, @Valid @RequestBody OrgItemCommand command) {
        return service.update(kindOf(kind), id, command);
    }

    @DeleteMapping(KIND + "/{id}")
    ResponseEntity<Void> inactivate(@PathVariable String kind, @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        service.inactivate(kindOf(kind), id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(KIND + "/{id}/restore")
    OrgItemView restore(@PathVariable String kind, @PathVariable UUID id) {
        return service.restore(kindOf(kind), id);
    }

    @DeleteMapping(KIND + "/{id}/forever")
    ResponseEntity<Void> deleteForever(@PathVariable String kind, @PathVariable UUID id) {
        service.deleteForever(kindOf(kind), id);
        return ResponseEntity.noContent().build();
    }

    private static OrgKind kindOf(String path) {
        return OrgKind.fromPath(path).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
