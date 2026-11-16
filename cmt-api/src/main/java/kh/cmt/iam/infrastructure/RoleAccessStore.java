package kh.cmt.iam.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.iam.domain.ApprovalLimits;
import kh.cmt.iam.domain.LoginRules;
import kh.cmt.shared.security.ScopeType;
import kh.cmt.shared.security.SensitiveField;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The role settings besides the permission table: the record scope ({@code role.scope_type},
 * {@code role_scope_department}) and, in {@code role_settings}, the sensitive fields, approval limits and login rules.
 * Plain SQL on purpose: it is read while scoped queries are being prepared.
 */
@Repository
public class RoleAccessStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RoleAccessStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Scope and sensitive fields of a role as stored, with the permission version they belong to. */
    public record StoredAccess(int permVersion, ScopeType scopeType, Set<UUID> departmentIds, Set<SensitiveField> sensitive) {
    }

    /** The access of an active role, by code. Empty if the school has no such active role. */
    public Optional<StoredAccess> findActive(UUID schoolId, String code) {
        return jdbc.sql("""
                        select r.perm_version, r.scope_type,
                               array(select d.department_id from role_scope_department d where d.role_id = r.id) as departments,
                               array(select f.key from role_settings s, jsonb_each(s.sensitive) f
                                     where s.role_id = r.id and f.value = 'true'::jsonb) as sensitive
                        from role r
                        where r.school_id = :s and r.code = :c and not r.inactive""")
                .param("s", schoolId).param("c", code)
                .query((rs, n) -> {
                    Set<UUID> departments = new LinkedHashSet<>();
                    for (Object id : (Object[]) rs.getArray("departments").getArray()) {
                        departments.add((UUID) id);
                    }
                    Set<SensitiveField> sensitive = EnumSet.noneOf(SensitiveField.class);
                    for (Object key : (Object[]) rs.getArray("sensitive").getArray()) {
                        SensitiveField.fromKey((String) key).ifPresent(sensitive::add);
                    }
                    return new StoredAccess(rs.getInt("perm_version"), ScopeType.valueOf(rs.getString("scope_type")),
                            departments, sensitive);
                })
                .optional();
    }

    // ------------------------------------------------------------------ scope departments

    public Set<UUID> departments(UUID roleId) {
        return new LinkedHashSet<>(jdbc.sql("select department_id from role_scope_department where role_id = :r order by department_id")
                .param("r", roleId).query(UUID.class).list());
    }

    /** The scope departments of every role of a school, by role id. */
    public Map<UUID, Set<UUID>> departmentsForSchool(UUID schoolId) {
        Map<UUID, Set<UUID>> all = new HashMap<>();
        jdbc.sql("select role_id, department_id from role_scope_department where school_id = :s order by department_id")
                .param("s", schoolId)
                .query((rs, n) -> all.computeIfAbsent(rs.getObject("role_id", UUID.class), id -> new LinkedHashSet<>())
                        .add(rs.getObject("department_id", UUID.class)))
                .list();
        return all;
    }

    public void replaceDepartments(UUID schoolId, UUID roleId, Set<UUID> departmentIds) {
        jdbc.sql("delete from role_scope_department where role_id = :r").param("r", roleId).update();
        for (UUID department : departmentIds) {
            jdbc.sql("insert into role_scope_department (school_id, role_id, department_id) values (:s, :r, :d)")
                    .param("s", schoolId).param("r", roleId).param("d", department).update();
        }
    }

    // ------------------------------------------------------------------ sensitive fields

    public Set<SensitiveField> sensitive(UUID roleId) {
        Set<SensitiveField> fields = EnumSet.noneOf(SensitiveField.class);
        jdbc.sql("select f.key from role_settings s, jsonb_each(s.sensitive) f where s.role_id = :r and f.value = 'true'::jsonb")
                .param("r", roleId).query(String.class).list()
                .forEach(key -> SensitiveField.fromKey(key).ifPresent(fields::add));
        return fields;
    }

    /** The sensitive fields of every role of a school, by role id. */
    public Map<UUID, Set<SensitiveField>> sensitiveForSchool(UUID schoolId) {
        Map<UUID, Set<SensitiveField>> all = new HashMap<>();
        jdbc.sql("select s.role_id, f.key from role_settings s, jsonb_each(s.sensitive) f where s.school_id = :s and f.value = 'true'::jsonb")
                .param("s", schoolId)
                .query((rs, n) -> {
                    Set<SensitiveField> fields = all.computeIfAbsent(rs.getObject("role_id", UUID.class), id -> EnumSet.noneOf(SensitiveField.class));
                    SensitiveField.fromKey(rs.getString("key")).ifPresent(fields::add);
                    return null;
                })
                .list();
        return all;
    }

    /** The settings row of a new role. */
    public void createSettings(UUID schoolId, UUID roleId, Set<SensitiveField> visible, ApprovalLimits limits, LoginRules rules) {
        jdbc.sql("""
                        insert into role_settings (role_id, school_id, sensitive, approval_limits, login_rules)
                        values (:r, :s, cast(:sensitive as jsonb), cast(:limits as jsonb), cast(:rules as jsonb))""")
                .param("r", roleId).param("s", schoolId).param("sensitive", sensitiveJson(visible))
                .param("limits", write(limits)).param("rules", write(rules)).update();
    }

    /** Stores the sensitive fields of a role: every group is written, true or false. */
    public void saveSensitive(UUID roleId, Set<SensitiveField> visible) {
        jdbc.sql("update role_settings set sensitive = cast(:j as jsonb) where role_id = :r")
                .param("r", roleId).param("j", sensitiveJson(visible)).update();
    }

    private static String sensitiveJson(Set<SensitiveField> visible) {
        return java.util.Arrays.stream(SensitiveField.values())
                .map(f -> "\"" + f.key() + "\": " + visible.contains(f))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    // ------------------------------------------------------------------ approval limits and login rules

    public ApprovalLimits approvalLimits(UUID roleId) {
        return read(jdbc.sql("select approval_limits::text from role_settings where role_id = :r").param("r", roleId)
                .query(String.class).single(), ApprovalLimits.class);
    }

    public LoginRules loginRules(UUID roleId) {
        return read(jdbc.sql("select login_rules::text from role_settings where role_id = :r").param("r", roleId)
                .query(String.class).single(), LoginRules.class);
    }

    /** Approval limits and login rules of every role of a school, by role id. */
    public Map<UUID, ApprovalLimits> approvalLimitsForSchool(UUID schoolId) {
        Map<UUID, ApprovalLimits> all = new HashMap<>();
        jdbc.sql("select role_id, approval_limits::text as v from role_settings where school_id = :s").param("s", schoolId)
                .query((rs, n) -> all.put(rs.getObject("role_id", UUID.class), read(rs.getString("v"), ApprovalLimits.class))).list();
        return all;
    }

    public Map<UUID, LoginRules> loginRulesForSchool(UUID schoolId) {
        Map<UUID, LoginRules> all = new HashMap<>();
        jdbc.sql("select role_id, login_rules::text as v from role_settings where school_id = :s").param("s", schoolId)
                .query((rs, n) -> all.put(rs.getObject("role_id", UUID.class), read(rs.getString("v"), LoginRules.class))).list();
        return all;
    }

    /** Approval limits of the active roles of a school, by role code. */
    public Map<String, ApprovalLimits> activeApprovalLimitsByCode(UUID schoolId) {
        Map<String, ApprovalLimits> all = new HashMap<>();
        jdbc.sql("""
                        select r.code, s.approval_limits::text as v from role r join role_settings s on s.role_id = r.id
                        where r.school_id = :s and not r.inactive""").param("s", schoolId)
                .query((rs, n) -> all.put(rs.getString("code"), read(rs.getString("v"), ApprovalLimits.class))).list();
        return all;
    }

    /** The active role of the person's active web user, else the school's default role for new web users. */
    public Optional<String> roleOfPerson(UUID schoolId, UUID personId) {
        return jdbc.sql("""
                        select coalesce(
                          (select w.role_code from web_user w join role r on r.school_id = w.school_id and r.code = w.role_code and not r.inactive
                           where w.school_id = :s and w.linked_person_id = :p and not w.inactive and w.deleted_at is null limit 1),
                          (select r.code from role r where r.school_id = :s and r.is_default and not r.inactive limit 1))""")
                .param("s", schoolId).param("p", personId).query(String.class).optional();
    }

    public void saveApprovalLimits(UUID roleId, ApprovalLimits limits) {
        jdbc.sql("update role_settings set approval_limits = cast(:j as jsonb) where role_id = :r")
                .param("r", roleId).param("j", write(limits)).update();
    }

    public void saveLoginRules(UUID roleId, LoginRules rules) {
        jdbc.sql("update role_settings set login_rules = cast(:j as jsonb) where role_id = :r")
                .param("r", roleId).param("j", write(rules)).update();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable role setting: " + text, e);
        }
    }
}
