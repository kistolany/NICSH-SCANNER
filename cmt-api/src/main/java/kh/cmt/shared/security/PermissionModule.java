package kh.cmt.shared.security;

import static kh.cmt.shared.security.PermissionAction.APPROVE;
import static kh.cmt.shared.security.PermissionAction.CREATE;
import static kh.cmt.shared.security.PermissionAction.DELETE;
import static kh.cmt.shared.security.PermissionAction.EDIT;
import static kh.cmt.shared.security.PermissionAction.EXPORT;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The areas of the Admin Web a role can be given access to (the rows of the permission table), with the
 * actions that make no sense there. In JSON and in the database the key is used, for example {@code shifts}.
 * <p>
 * Rules: the actions listed as not applicable can never be granted; any other action needs {@code view} in the
 * same module.
 */
public enum PermissionModule {

    DASHBOARD("dashboard", EnumSet.of(CREATE, EDIT, DELETE, APPROVE)),
    APPROVALS("approvals", EnumSet.of(CREATE, DELETE)),
    ATTENDANCE("attendance", EnumSet.of(CREATE, DELETE, APPROVE)),
    PEOPLE("people", EnumSet.of(APPROVE)),
    SHIFTS("shifts", EnumSet.of(APPROVE)),
    LEAVE("leave", EnumSet.noneOf(PermissionAction.class)),
    QR("qr", EnumSet.of(APPROVE, EXPORT)),
    ANNOUNCEMENTS("announcements", EnumSet.of(APPROVE)),
    REPORTS("reports", EnumSet.of(CREATE, EDIT, DELETE, APPROVE)),
    USERS("users", EnumSet.of(APPROVE, EXPORT)),
    SETTINGS("settings", EnumSet.of(CREATE, DELETE, APPROVE, EXPORT)),
    LOG("log", EnumSet.of(CREATE, EDIT, DELETE, APPROVE));

    private final String key;
    private final Set<PermissionAction> notApplicable;

    PermissionModule(String key, Set<PermissionAction> notApplicable) {
        this.key = key;
        this.notApplicable = notApplicable;
    }

    public String key() {
        return key;
    }

    public boolean applies(PermissionAction action) {
        return !notApplicable.contains(action);
    }

    /** All the actions that can be granted in this module, in the usual order. */
    public Set<PermissionAction> applicableActions() {
        Set<PermissionAction> actions = EnumSet.allOf(PermissionAction.class);
        actions.removeAll(notApplicable);
        return actions;
    }

    public static Optional<PermissionModule> fromKey(String key) {
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
    }
}
