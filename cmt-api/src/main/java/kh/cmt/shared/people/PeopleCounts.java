package kh.cmt.shared.people;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How many active people use something, for pages of other modules: "people using it" on departments and positions,
 * "Sees: N people" on roles. Provided by the people module (the org and iam modules must not depend on it: people uses
 * them). Counts are for the whole school, whatever the caller's record scope.
 */
public interface PeopleCounts {

    /** Active people per department of the caller's school (departments without people are left out). */
    Map<UUID, Long> perDepartment();

    /** Active people per position of the caller's school. */
    Map<UUID, Long> perPosition();

    /** Active people of the caller's school, all of them or only those in these departments ({@code null} = all). */
    long active(Set<UUID> departments);
}
