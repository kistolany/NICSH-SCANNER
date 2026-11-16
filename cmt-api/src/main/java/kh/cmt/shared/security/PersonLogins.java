package kh.cmt.shared.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The app login of a person (the auth server's account with this {@code person_id}). Provided by the iam module, used
 * by the people module, which must not depend on iam (iam looks up people).
 */
public interface PersonLogins {

    /**
     * A new login. {@code username} null = made from the English name (or the Staff ID); {@code password} null = a
     * generated one, which is then returned (once) in {@link Credentials}.
     */
    record NewLogin(String username, String password, boolean mustChangePassword, String role, String phone,
            String nameEn, String staffId, boolean active) {
    }

    /** The username, and the temporary password when one was generated (null when the admin typed it). */
    record Credentials(String username, String temporaryPassword) {
    }

    /** What the person page shows about the login. */
    record LoginInfo(String username, boolean active, boolean mustChangePassword, Instant lastLoginAt) {
    }

    /** Does the person have a login account (active or not)? */
    boolean exists(UUID personId);

    Optional<LoginInfo> info(UUID personId);

    /**
     * Creates the person's app account. Refused with {@code USERNAME_TAKEN} if the username is used by any login,
     * {@code PASSWORD_POLICY} if a typed password breaks the school's rules.
     */
    Credentials create(UUID personId, NewLogin login);

    /**
     * "Reset password" by an admin: {@code password} typed (checked against the school's rules), or null = a generated
     * one, returned once; it must be changed at the next login when asked or generated. The account is unlocked and
     * signed out everywhere. {@code NO_LOGIN} without an account. Nothing is sent by email or SMS.
     */
    Credentials setPassword(UUID personId, String password, boolean mustChangePassword);

    /** Keeps the account in line with the person: role (token claim) and phone. No account: nothing. */
    void update(UUID personId, String role, String phone);

    /** The person's login stops working at once: account inactive, signed out everywhere. No account: nothing. */
    void disable(UUID personId);

    /** The person may sign in again. No account: nothing. */
    void enable(UUID personId);

    /**
     * Ends every login of the person's app account at once (refresh tokens revoked, older access tokens refused), e.g.
     * after their phone was reset or blocked. No account: nothing.
     */
    void signOutEverywhere(UUID personId);
}
