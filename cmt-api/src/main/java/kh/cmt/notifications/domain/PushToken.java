package kh.cmt.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;

/**
 * A phone's push token (FCM) for a person, with the app's language so pushes come in it. One row per token: when the
 * same token is registered again (another account on the phone, a new language) the row is taken over.
 */
@Entity
@Table(name = "push_token")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
public class PushToken extends BaseEntity {

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private String token;

    @Column(nullable = false)
    private String platform;

    @Column(nullable = false)
    private String locale;

    @Column(name = "device_id")
    private UUID deviceId;

    protected PushToken() {
    }

    public PushToken(String token) {
        this.token = token;
    }

    public void assign(UUID personId, UUID accountId, String platform, String locale, UUID deviceId) {
        this.personId = personId;
        this.accountId = accountId;
        this.platform = platform;
        this.locale = locale;
        this.deviceId = deviceId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getToken() {
        return token;
    }

    public String getPlatform() {
        return platform;
    }

    public String getLocale() {
        return locale;
    }

    public UUID getDeviceId() {
        return deviceId;
    }
}
