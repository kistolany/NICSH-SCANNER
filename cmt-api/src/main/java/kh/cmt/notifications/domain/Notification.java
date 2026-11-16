package kh.cmt.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.domain.BaseEntity;
import kh.cmt.shared.security.ScopeFilter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A notification for one person: a {@code kind} the app groups and translates (SCHEDULE…), a {@code code} and its
 * values ({@code params}). The text is made from them in the reader's language when it is shown or pushed. Limited to
 * the caller's record scope like the person (an app user sees only their own).
 */
@Entity
@Table(name = "notification")
@Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON_ID, deduceAliasInjectionPoints = false)
public class Notification extends BaseEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> params = new LinkedHashMap<>();

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(UUID personId, String kind, String code, Map<String, Object> params) {
        this.personId = personId;
        this.kind = kind;
        this.code = code;
        this.params = new LinkedHashMap<>(params);
    }

    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    public UUID getPersonId() {
        return personId;
    }

    public String getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
