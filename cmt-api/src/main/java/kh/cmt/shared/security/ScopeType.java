package kh.cmt.shared.security;

/**
 * Whose records a role may see (Roles page, "Record scope"). Applies to people and to everything that belongs to a
 * person (attendance, leave...). The permission table says what a role may do; the scope says to
 * whom.
 */
public enum ScopeType {

    /** All school. */
    ALL,

    /** Only people of the departments listed on the role. */
    SELECTED_DEPARTMENTS,

    /** Only people of the department of the user's own linked person. */
    OWN_DEPARTMENT,

    /** Only the user's linked person and the people who have that person as supervisor. */
    OWN_TEAM
}
