package kh.cmt.iam.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.iam.infrastructure.RoleAccessStore.StoredAccess;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PersonDepartments;
import kh.cmt.shared.security.RecordScope;
import kh.cmt.shared.security.RecordScopes;
import kh.cmt.shared.security.ScopeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The record scope of the caller, from the scope of their role.
 * <p>
 * It is asked for every time a scoped query runs, so it is kept cheap: once per HTTP request it is worked out and
 * remembered in the request, and the role's scope comes from {@link RoleAccessCache} (Redis, keyed by permission
 * version, so a change applies on the next request). Only plain SQL is used here (no scoped entity queries).
 * <p>
 * Fails closed: a role that does not exist, "own department" or "own team" without a linked person, and any unknown
 * kind of caller see nobody.
 */
@Service
public class RecordScopeService implements RecordScopes {

    private static final String REQUEST_KEY = RecordScopeService.class.getName() + ".scope";
    private static final String APP_ADMIN = "admin";

    private final RoleAccessCache roles;
    private final ObjectProvider<PersonDepartments> personDepartments;

    RecordScopeService(RoleAccessCache roles, ObjectProvider<PersonDepartments> personDepartments) {
        this.roles = roles;
        this.personDepartments = personDepartments;
    }

    @Override
    public RecordScope current() {
        if (ScopeFilter.bypassed()) {
            return RecordScope.everyone();
        }
        Optional<CurrentUser> user = CurrentUser.current();
        if (user.isEmpty()) {
            return RecordScope.everyone();   // scheduled jobs, start-up: there is no user to limit
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST) instanceof Remembered known
                && known.user().equals(user.get())) {
            return known.scope();
        }
        RecordScope scope = of(user.get());
        if (request != null) {
            request.setAttribute(REQUEST_KEY, new Remembered(user.get(), scope), RequestAttributes.SCOPE_REQUEST);
        }
        return scope;
    }

    /** The scope worked out for a caller, kept for the rest of the request. */
    private record Remembered(CurrentUser user, RecordScope scope) {
    }

    @Override
    public RecordScope of(CurrentUser user) {
        if (user.isWeb()) {
            return roles.of(user.schoolId(), user.role()).map(stored -> resolve(stored, user)).orElseGet(RecordScope::nobody);
        }
        if (user.isApp()) {
            if (APP_ADMIN.equals(user.role())) {
                return RecordScope.everyone();   // the admin app works for the whole school
            }
            return user.personId() == null ? RecordScope.nobody() : RecordScope.self(user.personId());
        }
        if (CurrentUser.JOB.equals(user.type())) {
            return RecordScope.everyone();
        }
        return RecordScope.nobody();
    }

    private RecordScope resolve(StoredAccess stored, CurrentUser user) {
        return switch (stored.scopeType()) {
            case ALL -> RecordScope.everyone();
            case SELECTED_DEPARTMENTS -> RecordScope.departments(stored.departmentIds());
            case OWN_DEPARTMENT -> ownDepartment(user.personId())
                    .map(department -> RecordScope.departments(Set.of(department)))
                    .orElseGet(RecordScope::nobody);
            case OWN_TEAM -> user.personId() == null ? RecordScope.nobody() : RecordScope.teamOf(user.personId());
        };
    }

    private Optional<UUID> ownDepartment(UUID personId) {
        PersonDepartments directory = personDepartments.getIfAvailable();
        if (personId == null || directory == null) {
            return Optional.empty();
        }
        return ScopeFilter.withoutScope(() -> directory.departmentOf(personId));
    }
}
