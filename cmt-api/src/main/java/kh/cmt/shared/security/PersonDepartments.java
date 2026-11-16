package kh.cmt.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * The department a person belongs to, for the "own department" scope. Provided by the people module (phase 4);
 * until it exists nobody has a department, so an "own department" role sees nobody.
 * <p>
 * It is called while a scoped query is being prepared, so it must read the database with plain SQL, not with a
 * query on a scoped entity (that would ask for the scope again).
 */
public interface PersonDepartments {

    Optional<UUID> departmentOf(UUID personId);
}
