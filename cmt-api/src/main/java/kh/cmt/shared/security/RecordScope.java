package kh.cmt.shared.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The people the current caller may see, worked out from their role's {@link ScopeType} (see {@link RecordScopes}).
 * <ul>
 *   <li>{@code ALL}: everybody of the school</li>
 *   <li>{@code DEPARTMENTS}: people of {@code departmentIds}</li>
 *   <li>{@code TEAM}: the person {@code personId} and the people whose supervisor that is</li>
 *   <li>{@code SELF}: only the person {@code personId} (app users looking at their own records)</li>
 *   <li>{@code NONE}: nobody (a role that no longer exists, or "own department" without a linked person)</li>
 * </ul>
 * Queries apply it on their own through {@link ScopeFilter}; this object is for the few places that check a single
 * record in code ({@link #covers}) or write plain SQL ({@link #sql}).
 */
public record RecordScope(Mode mode, Set<UUID> departmentIds, UUID personId) {

    public enum Mode { ALL, DEPARTMENTS, TEAM, SELF, NONE }

    /** Used where an SQL parameter needs a value but the mode does not use it. */
    static final UUID NO_PERSON = new UUID(0, 0);

    public RecordScope {
        departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
    }

    public static RecordScope everyone() {
        return new RecordScope(Mode.ALL, Set.of(), null);
    }

    public static RecordScope departments(Collection<UUID> departmentIds) {
        return new RecordScope(Mode.DEPARTMENTS, Set.copyOf(departmentIds), null);
    }

    public static RecordScope teamOf(UUID personId) {
        return new RecordScope(Mode.TEAM, Set.of(), personId);
    }

    public static RecordScope self(UUID personId) {
        return new RecordScope(Mode.SELF, Set.of(), personId);
    }

    public static RecordScope nobody() {
        return new RecordScope(Mode.NONE, Set.of(), null);
    }

    public boolean isEveryone() {
        return mode == Mode.ALL;
    }

    /** May the caller see this person? Same rule as the query filter, for a record already in memory. */
    public boolean covers(UUID personId, UUID departmentId, UUID supervisorId) {
        return switch (mode) {
            case ALL -> true;
            case DEPARTMENTS -> departmentId != null && departmentIds.contains(departmentId);
            case TEAM -> this.personId.equals(personId) || this.personId.equals(supervisorId);
            case SELF -> this.personId.equals(personId);
            case NONE -> false;
        };
    }

    /**
     * The scope as an SQL condition on a person table with the given alias (columns {@code id},
     * {@code department_id}, {@code supervisor_id}), for queries written by hand, such as exports:
     * <pre>
     * SqlCondition scope = scopes.current().sql("p");
     * jdbc.sql("select ... from person p where p.school_id = :school and " + scope.sql())
     *     .params(scope.params()).param("school", school)...
     * </pre>
     */
    public SqlCondition sql(String alias) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(ScopeFilter.MODE, mode.name());
        params.put(ScopeFilter.DEPARTMENTS, departmentArray());
        params.put(ScopeFilter.LEAD, leadOrNone());
        return new SqlCondition(ScopeFilter.ON_PERSON.replace("{alias}", alias), Map.copyOf(params));
    }

    /** Departments as a Postgres array literal: {@code {id,id}}. */
    String departmentArray() {
        return departmentIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(",", "{", "}"));
    }

    UUID leadOrNone() {
        return personId == null ? NO_PERSON : personId;
    }

    /** An SQL condition with its named parameters. */
    public record SqlCondition(String sql, Map<String, Object> params) {
    }
}
