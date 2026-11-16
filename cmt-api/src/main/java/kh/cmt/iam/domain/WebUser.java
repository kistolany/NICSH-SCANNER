package kh.cmt.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.shared.domain.SoftDeletable;

/**
 * An office user of the Admin Web (Web users &amp; roles &gt; Web users), separate from app users. Its login is the
 * auth server's account {@code accountId}, which the iam module keeps in line with it. Deactivated = inactive.
 * <p>
 * What the user may do and see comes from the role ({@code roleCode}): permissions, record scope, sensitive fields,
 * approval limits, login rules. {@code linkedPersonId} is the user's own person record (for "own department" /
 * "own team" scopes and their own requests); a web user is not a record that belongs to a person, so it is not
 * itself filtered by record scope.
 */
@Entity
@Table(name = "web_user")
public class WebUser extends SoftDeletable {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    private String name;

    private String username;

    private String phone;

    @Column(name = "role_code")
    private String roleCode;

    @Column(name = "linked_person_id")
    private UUID linkedPersonId;

    @Column(name = "two_factor_required")
    private boolean twoFactorRequired;

    /** Last day the user may sign in (school time); null = no end. */
    @Column(name = "access_expires_on")
    private LocalDate accessExpiresOn;

    @Column(name = "access_expires_reason")
    private String accessExpiresReason;

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public UUID getLinkedPersonId() {
        return linkedPersonId;
    }

    public void setLinkedPersonId(UUID linkedPersonId) {
        this.linkedPersonId = linkedPersonId;
    }

    public boolean isTwoFactorRequired() {
        return twoFactorRequired;
    }

    public void setTwoFactorRequired(boolean twoFactorRequired) {
        this.twoFactorRequired = twoFactorRequired;
    }

    public LocalDate getAccessExpiresOn() {
        return accessExpiresOn;
    }

    public String getAccessExpiresReason() {
        return accessExpiresReason;
    }

    public void setAccessExpires(LocalDate lastDay, String reason) {
        this.accessExpiresOn = lastDay;
        this.accessExpiresReason = lastDay == null ? null : reason;
    }

    /** Active and still within its access period. */
    public boolean canSignIn(LocalDate today) {
        return !isInactive() && (accessExpiresOn == null || !today.isAfter(accessExpiresOn));
    }
}
