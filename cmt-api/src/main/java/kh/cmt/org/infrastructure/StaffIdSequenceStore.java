package kh.cmt.org.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The counters behind the Staff IDs (table staff_id_sequence). {@link #take} locks its row until the calling
 * transaction ends: that is what keeps two parallel creations from receiving the same number.
 */
@Repository
public class StaffIdSequenceStore {

    private final JdbcClient jdbc;

    public StaffIdSequenceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The number the next person will get. Takes no lock and uses nothing up. */
    public long peekNext(UUID schoolId, String prefix, int year) {
        return jdbc.sql("select last_value from staff_id_sequence where school_id = :s and prefix = :p and year = :y")
                .param("s", schoolId).param("p", prefix).param("y", year)
                .query(Long.class).optional().orElse(0L) + 1;
    }

    /**
     * Takes the next number: one statement that creates the counter or adds 1 to it, and keeps the row locked
     * until the end of the transaction. A second caller waits here and then gets the following number.
     */
    public long take(UUID schoolId, String prefix, int year) {
        return jdbc.sql("""
                insert into staff_id_sequence (school_id, prefix, year, last_value) values (:s, :p, :y, 1)
                on conflict (school_id, prefix, year)
                do update set last_value = staff_id_sequence.last_value + 1, updated_at = now()
                returning last_value
                """)
                .param("s", schoolId).param("p", prefix).param("y", year)
                .query(Long.class).single();
    }

    /**
     * Moves the counter forward so that {@code nextNumber} is the number the next person gets. Refuses to go back
     * (numbers are never reused). Returns false if {@code nextNumber} is lower than the current next number.
     */
    public boolean advanceTo(UUID schoolId, String prefix, int year, long nextNumber) {
        jdbc.sql("insert into staff_id_sequence (school_id, prefix, year, last_value) values (:s, :p, :y, 0) "
                + "on conflict (school_id, prefix, year) do nothing")
                .param("s", schoolId).param("p", prefix).param("y", year).update();
        long last = jdbc.sql("select last_value from staff_id_sequence where school_id = :s and prefix = :p and year = :y for update")
                .param("s", schoolId).param("p", prefix).param("y", year).query(Long.class).single();
        if (nextNumber - 1 < last) {
            return false;
        }
        jdbc.sql("update staff_id_sequence set last_value = :v, updated_at = now() "
                + "where school_id = :s and prefix = :p and year = :y")
                .param("v", nextNumber - 1).param("s", schoolId).param("p", prefix).param("y", year).update();
        return true;
    }
}
