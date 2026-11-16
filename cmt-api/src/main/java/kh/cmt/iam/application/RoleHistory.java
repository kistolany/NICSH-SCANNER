package kh.cmt.iam.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.iam.domain.Role;
import kh.cmt.iam.infrastructure.RoleHistoryStore;
import kh.cmt.shared.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * The change history of the roles (Roles page, "Change history"): every saved version with who, when and what, so
 * an older one can be restored. A role that existed before the history did gets its state saved as the first
 * version just before its first change.
 */
@Component
class RoleHistory {

    /** What a version changed. */
    enum Change { CREATED, RENAMED, PERMISSIONS, SCOPE, SENSITIVE_FIELDS, APPROVAL_LIMITS, LOGIN_RULES, TWO_FACTOR, RESTORED }

    private final RoleHistoryStore store;
    private final ObjectMapper json;
    private final Clock clock;

    RoleHistory(RoleHistoryStore store, ObjectMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    /** Before the first change of a role without history: its current state becomes version 1. */
    void ensureBaseline(Role role, RoleSnapshot current) {
        if (!store.exists(role.getId())) {
            store.add(role.getSchoolId(), role.getId(), Change.CREATED.name(), null, write(current), role.getCreatedBy(),
                    role.getCreatedAt() != null ? role.getCreatedAt() : clock.instant());
        }
    }

    /** A new version, by the caller (no caller = the system). */
    int record(Role role, Change change, Integer restoredFrom, RoleSnapshot snapshot) {
        UUID by = CurrentUser.current().map(CurrentUser::userId).orElse(null);
        return store.add(role.getSchoolId(), role.getId(), change.name(), restoredFrom, write(snapshot), by, clock.instant());
    }

    /** The first version of a system role: created by the system, whoever's request created it. */
    void recordSystemRole(Role role, RoleSnapshot snapshot) {
        store.add(role.getSchoolId(), role.getId(), Change.CREATED.name(), null, write(snapshot), null, clock.instant());
    }

    /** Newest first. */
    List<RoleHistoryStore.Row> list(UUID roleId) {
        return store.list(roleId);
    }

    Optional<RoleSnapshot> find(UUID roleId, int versionNo) {
        return store.find(roleId, versionNo).map(row -> read(row.snapshot()));
    }

    RoleSnapshot read(String text) {
        try {
            return json.readValue(text, RoleSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable role version", e);
        }
    }

    private String write(RoleSnapshot snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
