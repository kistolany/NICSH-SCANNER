package kh.cmt.iam.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the Admin Web sends to create and change web users. */
public final class WebUserCommands {

    private WebUserCommands() {
    }

    /**
     * A new web user. {@code role} is a role code (the default role for new web users when left out);
     * {@code accessExpiresOn} is the last day the user may sign in (null = no end), with an optional reason (e.g.
     * "External auditor"). The user gets a link to set their own password.
     */
    public record Create(String name, String username, String phone, String role, UUID linkedPersonId,
            Boolean twoFactorRequired, LocalDate accessExpiresOn, String accessExpiresReason, String password,
            Boolean mustChangePassword) {
    }

    /**
     * "Reset password": {@code password} typed by the admin, empty = generated (shown once); {@code mustChangePassword}
     * on unless false (always on for a generated one).
     */
    public record SetPassword(String password, Boolean mustChangePassword) {
    }

    /** The whole web user form; send the {@code version} that was read. */
    public record Update(String name, String username, String phone, String role, UUID linkedPersonId,
            Boolean twoFactorRequired, LocalDate accessExpiresOn, String accessExpiresReason, Long version) {
    }

    /** "Move to role" (Roles page, Users of this role): these web users get {@code role}. */
    public record MoveRole(List<UUID> userIds, String role) {
    }
}
