package kh.cmt.org.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgLabel;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionGuard;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import kh.cmt.shared.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Departments and positions: list, create, change, move to inactive, restore, delete forever. One use case set for
 * both kinds; {@link OrgItemType} holds what differs.
 * <p>
 * Permissions: see {@link #moduleOf}. "Delete forever" additionally needs the Super Admin role, as in the design.
 */
@Service
@Transactional
public class OrgItemService implements OrgQueries {

    static final String SUPER_ADMIN = "super_admin";
    static final int MAX_PAGE_SIZE = 500;

    private final Map<OrgKind, OrgItemType<?>> types = new EnumMap<>(OrgKind.class);

    private final PermissionGuard guard;

    OrgItemService(List<OrgItemType<?>> all, PermissionGuard guard) {
        this.guard = guard;
        all.forEach(type -> types.put(type.kind(), type));
    }

    /**
     * Departments and positions are managed on the People pages.
     * Reading the active lists (dropdowns) is open to every office user; the Inactive tab and every change need
     * a permission in that module.
     */
    static PermissionModule moduleOf(OrgKind kind) {
        return switch (kind) {
            case DEPARTMENT, POSITION -> PermissionModule.PEOPLE;
        };
    }

    @Transactional(readOnly = true)
    public PageResponse<OrgItemView> list(OrgKind kind, boolean inactive, String q, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", "page/size", "rule", "Range")));
        }
        if (inactive) {
            guard.require(moduleOf(kind), PermissionAction.VIEW);
        }
        return type(kind).list(inactive, q, page, size);
    }

    @Transactional(readOnly = true)
    public OrgItemView get(OrgKind kind, UUID id) {
        return type(kind).get(id);
    }

    public OrgItemView create(OrgKind kind, OrgItemCommand command) {
        guard.require(moduleOf(kind), PermissionAction.CREATE);
        return type(kind).create(command);
    }

    public OrgItemView update(OrgKind kind, UUID id, OrgItemCommand command) {
        guard.require(moduleOf(kind), PermissionAction.EDIT);
        return type(kind).update(id, command);
    }

    /** Moves the item to inactive (kept for history, hidden from dropdowns). {@code reason} is optional. */
    public void inactivate(OrgKind kind, UUID id, String reason) {
        guard.require(moduleOf(kind), PermissionAction.DELETE);
        type(kind).inactivate(id, CurrentUser.require().userId(), reason);
    }

    public OrgItemView restore(OrgKind kind, UUID id) {
        guard.require(moduleOf(kind), PermissionAction.EDIT);
        return type(kind).restore(id);
    }

    /** Removes an inactive item for good. Super Admin only; refused while other records still use it. */
    public void deleteForever(OrgKind kind, UUID id) {
        guard.require(moduleOf(kind), PermissionAction.DELETE);
        if (!SUPER_ADMIN.equals(CurrentUser.require().role())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        type(kind).deleteForever(id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(OrgKind kind, UUID id) {
        return type(kind).isActive(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrgLabel> labels(OrgKind kind, java.util.Collection<UUID> ids) {
        return type(kind).labels(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> activeByName(OrgKind kind, String name) {
        return name == null || name.isBlank() ? java.util.Optional.empty() : type(kind).activeByName(name.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> activeNames(OrgKind kind) {
        return type(kind).activeNames();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgLabel> active(OrgKind kind) {
        return type(kind).active();
    }

    private OrgItemType<?> type(OrgKind kind) {
        return types.get(kind);
    }
}
