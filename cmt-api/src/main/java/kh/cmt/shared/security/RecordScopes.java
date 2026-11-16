package kh.cmt.shared.security;

/**
 * Works out the {@link RecordScope} of a caller. Implemented by the iam module (from the role's scope).
 * <ul>
 *   <li>office users: the scope of their role</li>
 *   <li>app users with role {@code admin} (the admin app): all school</li>
 *   <li>other app users: only themselves</li>
 *   <li>integrations, and work that runs without a caller (scheduled jobs, start-up): all school</li>
 * </ul>
 */
public interface RecordScopes {

    /** The scope of the current caller (all school when nobody is signed in, or inside {@link ScopeFilter#withoutScope}). */
    RecordScope current();

    RecordScope of(CurrentUser user);
}
