package kh.cmt.org.api;

import java.util.function.Predicate;

/**
 * Hands out the next Staff ID. Used by the people module when a person is created without an ID.
 * <p>
 * Call it <b>inside the transaction that saves the person</b> (it refuses to run without one). The number is taken
 * with a database lock that is held until that transaction ends, so two people created at the same moment get
 * different numbers, and if the creation fails and rolls back the number is simply not used (no gap).
 */
public interface StaffIdGenerator {

    /**
     * @param alreadyUsed says whether an ID is taken by somebody (IDs can be typed in by hand or imported); such
     *                    IDs are skipped, and the numbers skipped are never offered again
     */
    String next(StaffRole role, Predicate<String> alreadyUsed);

    /** Same, when nobody can have taken the ID by other means. */
    default String next(StaffRole role) {
        return next(role, id -> false);
    }
}
