package kh.cmt.iam.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Clears the cached permissions of a role once the change that caused it is committed. (Clearing earlier could let
 * a parallel request cache the old table again before the new one is visible.)
 */
@Component
class PermissionCacheEvictor {

    private final PermissionService permissions;

    PermissionCacheEvictor(PermissionService permissions) {
        this.permissions = permissions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRoleChanged(RoleChanged event) {
        permissions.evict(event.schoolId(), event.roleCode());
    }
}
