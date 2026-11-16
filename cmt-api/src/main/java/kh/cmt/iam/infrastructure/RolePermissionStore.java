package kh.cmt.iam.infrastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.iam.domain.PermissionMatrix;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The rows of table role_permission, read and written as whole {@link PermissionMatrix} objects. */
@Repository
public class RolePermissionStore {

    private final JdbcClient jdbc;

    public RolePermissionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PermissionMatrix load(UUID roleId) {
        PermissionMatrix matrix = new PermissionMatrix();
        jdbc.sql("select module, action from role_permission where role_id = :r").param("r", roleId)
                .query((rs, n) -> Map.entry(rs.getString("module"), rs.getString("action"))).list()
                .forEach(pair -> PermissionModule.fromKey(pair.getKey()).ifPresent(m -> PermissionAction.fromKey(pair.getValue())
                        .ifPresent(a -> matrix.grant(m, a))));
        return matrix;
    }

    /** The matrices of every role of a school, by role id. */
    public Map<UUID, PermissionMatrix> loadForSchool(UUID schoolId) {
        Map<UUID, PermissionMatrix> all = new HashMap<>();
        jdbc.sql("select role_id, module, action from role_permission where school_id = :s").param("s", schoolId)
                .query((rs, n) -> {
                    all.computeIfAbsent(rs.getObject("role_id", UUID.class), id -> new PermissionMatrix())
                            .grant(PermissionModule.fromKey(rs.getString("module")).orElseThrow(),
                                    PermissionAction.fromKey(rs.getString("action")).orElseThrow());
                    return null;
                }).list();
        return all;
    }

    /** Replaces everything the role had with {@code matrix}. */
    public void replace(UUID schoolId, UUID roleId, PermissionMatrix matrix) {
        jdbc.sql("delete from role_permission where role_id = :r").param("r", roleId).update();
        for (var pair : matrix.pairs()) {
            jdbc.sql("insert into role_permission (school_id, role_id, module, action) values (:s, :r, :m, :a)")
                    .param("s", schoolId).param("r", roleId)
                    .param("m", pair.getKey().key()).param("a", pair.getValue().key()).update();
        }
    }

    /** Does any role of the school (deleted ones too) already use this code? */
    public boolean codeExists(UUID schoolId, String code) {
        return jdbc.sql("select count(*) from role where school_id = :s and code = :c")
                .param("s", schoolId).param("c", code).query(Integer.class).single() > 0;
    }
}
