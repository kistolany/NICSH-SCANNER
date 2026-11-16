package kh.cmt.shared.security;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Record scope in queries: the Hibernate filter {@value #NAME}.
 * <p>
 * The filter is defined on {@link kh.cmt.shared.domain.BaseEntity}, is on in every session, and asks
 * {@link RecordScopes} for the caller's scope each time a query runs, so nobody has to remember to switch it on and
 * the client never decides. It covers list queries, counts, paging, search and loading one record by id (an
 * out-of-scope record is simply not found). An entity takes part by declaring the condition for its shape:
 * <pre>
 * &#64;Filter(name = ScopeFilter.NAME, condition = ScopeFilter.ON_PERSON, deduceAliasInjectionPoints = false)
 * class Person ...      // the person table itself: id, department_id, supervisor_id
 * </pre>
 * Queries written in plain SQL (exports, reports) add {@link RecordScope#sql} themselves. Code that must look at the
 * whole school on behalf of a scoped user (for example "is this national ID already used?") wraps the call in
 * {@link #withoutScope}.
 */
public final class ScopeFilter {

    public static final String NAME = "recordScope";

    static final String MODE = "scopeMode";
    static final String DEPARTMENTS = "scopeDepartments";
    static final String LEAD = "scopePerson";

    /** Condition for the person table (alias placeholder {@code {alias}}). */
    public static final String ON_PERSON = "(:scopeMode = 'ALL'"
            + " or (:scopeMode = 'DEPARTMENTS' and {alias}.department_id = any(cast(:scopeDepartments as uuid[])))"
            + " or (:scopeMode = 'TEAM' and ({alias}.id = :scopePerson or {alias}.supervisor_id = :scopePerson))"
            + " or (:scopeMode = 'SELF' and {alias}.id = :scopePerson))";

    /**
     * Condition for a table whose rows belong to a person ({@code {alias}.person_id}): documents, attendance, leave…
     * A row is seen when its person is.
     */
    public static final String ON_PERSON_ID = "(:scopeMode = 'ALL' or exists (select 1 from person sp where sp.id = {alias}.person_id and ("
            + "(:scopeMode = 'DEPARTMENTS' and sp.department_id = any(cast(:scopeDepartments as uuid[])))"
            + " or (:scopeMode = 'TEAM' and (sp.id = :scopePerson or sp.supervisor_id = :scopePerson))"
            + " or (:scopeMode = 'SELF' and sp.id = :scopePerson))))";

    private static final ThreadLocal<Integer> BYPASS = ThreadLocal.withInitial(() -> 0);

    private ScopeFilter() {
    }

    /** Runs {@code work} seeing the whole school, whatever the caller's scope. */
    public static <T> T withoutScope(Supplier<T> work) {
        BYPASS.set(BYPASS.get() + 1);
        try {
            return work.get();
        } finally {
            int depth = BYPASS.get() - 1;
            if (depth == 0) {
                BYPASS.remove();
            } else {
                BYPASS.set(depth);
            }
        }
    }

    public static boolean bypassed() {
        return BYPASS.get() > 0;
    }

    // ------------------------------------------------------------------ filter parameters (made by Spring for Hibernate)

    /** Base of the parameter suppliers: the scope of the caller of the query being run. */
    abstract static class Parameter<T> implements Supplier<T> {

        private final ObjectProvider<RecordScopes> scopes;

        Parameter(ObjectProvider<RecordScopes> scopes) {
            this.scopes = scopes;
        }

        RecordScope scope() {
            return bypassed() ? RecordScope.everyone() : scopes.getObject().current();
        }
    }

    public static class ModeParameter extends Parameter<String> {

        public ModeParameter(ObjectProvider<RecordScopes> scopes) {
            super(scopes);
        }

        @Override
        public String get() {
            return scope().mode().name();
        }
    }

    public static class DepartmentsParameter extends Parameter<String> {

        public DepartmentsParameter(ObjectProvider<RecordScopes> scopes) {
            super(scopes);
        }

        @Override
        public String get() {
            return scope().departmentArray();
        }
    }

    public static class PersonParameter extends Parameter<UUID> {

        public PersonParameter(ObjectProvider<RecordScopes> scopes) {
            super(scopes);
        }

        @Override
        public UUID get() {
            return scope().leadOrNone();
        }
    }
}
