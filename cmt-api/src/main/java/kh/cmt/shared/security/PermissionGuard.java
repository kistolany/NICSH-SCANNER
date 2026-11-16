package kh.cmt.shared.security;

/**
 * Asks whether the caller may do something. The answer comes from the caller's role (token claim {@code role}):
 * for office users from the role's permission table; for app users with role {@code admin} from a fixed set (the
 * admin app); for everybody else nothing is allowed. Implemented by the iam module.
 */
public interface PermissionGuard {

    /** Does this caller hold the action in the module? */
    boolean allows(CurrentUser user, PermissionModule module, PermissionAction action);

    /** Does the current caller hold it? False when nobody is signed in. */
    default boolean allows(PermissionModule module, PermissionAction action) {
        return CurrentUser.current().map(user -> allows(user, module, action)).orElse(false);
    }

    /**
     * Refuses the call with {@code 403 FORBIDDEN} (and the module and action in the error body) unless the
     * current caller holds the action in the module.
     */
    void require(PermissionModule module, PermissionAction action);
}
