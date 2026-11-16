package kh.cmt.iam.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The saved versions of the roles (table {@code role_version}); the snapshot is stored as JSON. */
@Repository
public class RoleHistoryStore {

    /** One saved version. {@code changedBy} is an account id (null = the system). */
    public record Row(int versionNo, String change, Integer restoredFrom, String snapshot, UUID changedBy, Instant changedAt) {
    }

    private final JdbcClient jdbc;

    public RoleHistoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID roleId) {
        return jdbc.sql("select count(*) from role_version where role_id = :r").param("r", roleId).query(Integer.class).single() > 0;
    }

    /** Adds the next version; returns its number. */
    public int add(UUID schoolId, UUID roleId, String change, Integer restoredFrom, String snapshot, UUID changedBy, Instant at) {
        int next = jdbc.sql("select coalesce(max(version_no), 0) + 1 from role_version where role_id = :r")
                .param("r", roleId).query(Integer.class).single();
        jdbc.sql("""
                        insert into role_version (id, school_id, role_id, version_no, change, restored_from, snapshot,
                                                  changed_by, changed_at)
                        values (:id, :s, :r, :v, :change, :from, cast(:snapshot as jsonb), :by, :at)""")
                .param("id", UUID.randomUUID()).param("s", schoolId).param("r", roleId).param("v", next)
                .param("change", change).param("from", restoredFrom).param("snapshot", snapshot)
                .param("by", changedBy).param("at", Timestamp.from(at))
                .update();
        return next;
    }

    /** Newest first. */
    public List<Row> list(UUID roleId) {
        return jdbc.sql("""
                        select version_no, change, restored_from, snapshot::text as snapshot, changed_by, changed_at
                        from role_version where role_id = :r order by version_no desc""")
                .param("r", roleId).query((rs, n) -> row(rs)).list();
    }

    public Optional<Row> find(UUID roleId, int versionNo) {
        return jdbc.sql("""
                        select version_no, change, restored_from, snapshot::text as snapshot, changed_by, changed_at
                        from role_version where role_id = :r and version_no = :v""")
                .param("r", roleId).param("v", versionNo).query((rs, n) -> row(rs)).optional();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getInt("version_no"), rs.getString("change"), rs.getObject("restored_from", Integer.class),
                rs.getString("snapshot"), rs.getObject("changed_by", UUID.class), rs.getTimestamp("changed_at").toInstant());
    }
}
