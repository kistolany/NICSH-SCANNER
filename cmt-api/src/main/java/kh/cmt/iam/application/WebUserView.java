package kh.cmt.iam.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.Sensitive;
import kh.cmt.shared.security.SensitiveField;

/**
 * A web user as the Admin Web shows it.
 * <ul>
 *   <li>{@code status}: ACTIVE, EXPIRED (after the last day of {@code accessExpiresOn}) or INACTIVE (deactivated)</li>
 *   <li>{@code expiresInDays}: days left until access ends (0 = today is the last day), when there is an end date</li>
 *   <li>{@code scope}: the record scope of the role (read-only here: the role decides it)</li>
 *   <li>{@code twoFactor}: required for the user, required by the role, authenticator set up</li>
 *   <li>{@code phone}: for the SMS code of two-step verification; a sensitive field (phone numbers)</li>
 *   <li>{@code version}: send it back when saving</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebUserView(UUID id, String name, String username, @Sensitive(SensitiveField.PHONE) String phone,
        RoleRef role, RoleView.ScopeView scope, UUID linkedPersonId, TwoFactor twoFactor, String status,
        LocalDate accessExpiresOn, String accessExpiresReason, Integer expiresInDays, Instant lastLoginAt,
        Deactivation deactivated, long version, Instant createdAt, Instant updatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";
    public static final String INACTIVE = "INACTIVE";

    /** The role: code and name (null name if the role was deleted). */
    public record RoleRef(String code, LocalizedName name) {
    }

    /** {@code required}: for this user; {@code requiredByRole}: by the role's rule; {@code enrolled}: app set up. */
    public record TwoFactor(boolean required, boolean requiredByRole, boolean enrolled) {
    }

    /** Who deactivated the user, when, and why. */
    public record Deactivation(Instant at, UUID by, String reason) {
    }
}
