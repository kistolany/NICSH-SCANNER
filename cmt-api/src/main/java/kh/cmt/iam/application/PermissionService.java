package kh.cmt.iam.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.iam.domain.AppAdminAccess;
import kh.cmt.iam.domain.PermissionMatrix;
import kh.cmt.iam.domain.Role;
import kh.cmt.iam.infrastructure.RolePermissionStore;
import kh.cmt.iam.infrastructure.RoleRepository;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionGuard;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Decides what the caller may do, from the role in the access token.
 * <p>
 * The token holds only the role; the permission table is looked up here, so a change to a role applies to the
 * very next request, without logging anybody out. To keep that cheap the table is cached in Redis:
 * <pre>
 * perm:ver:{school}:{role}        current permission version of the role      (10 minutes)
 * perm:{school}:{role}:{version}  the permission table of that version        (1 hour)
 * </pre>
 * When a role changes, the version key is removed after the change is committed; the next request reads the new
 * version from the database and finds (or caches) the table under the new version. If Redis cannot be reached the
 * database is used directly: slower, never wrong.
 */
@Service
public class PermissionService implements PermissionGuard {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);
    private static final Duration VERSION_TTL = Duration.ofMinutes(10);
    private static final Duration TABLE_TTL = Duration.ofHours(1);

    private final RoleRepository roles;
    private final RolePermissionStore permissions;
    private final RoleSeeder seeder;
    private final StringRedisTemplate redis;

    PermissionService(RoleRepository roles, RolePermissionStore permissions, RoleSeeder seeder, StringRedisTemplate redis) {
        this.roles = roles;
        this.permissions = permissions;
        this.seeder = seeder;
        this.redis = redis;
    }

    @Override
    public boolean allows(CurrentUser user, PermissionModule module, PermissionAction action) {
        if (user.isWeb()) {
            return tableOf(user.schoolId(), user.role()).allows(module, action);
        }
        if (user.isApp() && "admin".equals(user.role())) {
            return AppAdminAccess.matrix().allows(module, action);
        }
        return false;   // staff and teachers use the app screens (their own data); integrations have no admin access
    }

    /**
     * What the caller may do, as module key -> action keys (modules without any grant left out), for the Admin Web
     * to build its menu and buttons. The server still checks every request.
     */
    public Map<String, List<String>> grantsOf(CurrentUser user) {
        PermissionMatrix matrix = user.isWeb() ? tableOf(user.schoolId(), user.role())
                : user.isApp() && "admin".equals(user.role()) ? AppAdminAccess.matrix() : new PermissionMatrix();
        Map<String, List<String>> grants = new LinkedHashMap<>();
        for (var pair : matrix.pairs()) {
            grants.computeIfAbsent(pair.getKey().key(), k -> new ArrayList<>()).add(pair.getValue().key());
        }
        return grants;
    }

    @Override
    public void require(PermissionModule module, PermissionAction action) {
        CurrentUser user = CurrentUser.require();
        if (!allows(user, module, action)) {
            throw new ApiException(ErrorCode.FORBIDDEN).with("module", module.key()).with("action", action.key());
        }
    }

    // ------------------------------------------------------------------ the table of a role (cached)

    PermissionMatrix tableOf(UUID schoolId, String roleCode) {
        if (roleCode == null) {
            return new PermissionMatrix();
        }
        try {
            String versionKey = "perm:ver:" + schoolId + ":" + roleCode;
            String version = redis.opsForValue().get(versionKey);
            if (version == null) {
                version = String.valueOf(currentVersion(schoolId, roleCode));
                redis.opsForValue().set(versionKey, version, VERSION_TTL);
            }
            String tableKey = "perm:" + schoolId + ":" + roleCode + ":" + version;
            String cached = redis.opsForValue().get(tableKey);
            if (cached != null) {
                return decode(cached);
            }
            PermissionMatrix loaded = "0".equals(version) ? new PermissionMatrix() : loadFromDatabase(schoolId, roleCode);
            redis.opsForValue().set(tableKey, encode(loaded), TABLE_TTL);
            return loaded;
        } catch (RuntimeException redisProblem) {
            log.error("Permission cache unavailable, reading the database (role {})", roleCode, redisProblem);
            return loadFromDatabase(schoolId, roleCode);
        }
    }

    /** Forgets what is cached about a role. Called after a change has been committed. */
    void evict(UUID schoolId, String roleCode) {
        try {
            redis.delete("perm:ver:" + schoolId + ":" + roleCode);
        } catch (RuntimeException redisProblem) {
            log.error("Could not clear the permission cache for role {}", roleCode, redisProblem);
        }
    }

    /** The permission version of an active role; 0 if there is no such role (it then grants nothing). */
    private int currentVersion(UUID schoolId, String roleCode) {
        return findRole(schoolId, roleCode).map(Role::getPermVersion).orElse(0);
    }

    private PermissionMatrix loadFromDatabase(UUID schoolId, String roleCode) {
        return findRole(schoolId, roleCode).map(role -> permissions.load(role.getId())).orElseGet(PermissionMatrix::new);
    }

    private java.util.Optional<Role> findRole(UUID schoolId, String roleCode) {
        var found = roles.findBySchoolIdAndCode(schoolId, roleCode);
        if (found.isEmpty()) {
            // a school that has never opened the roles page has no roles yet: create the system roles and look again
            try {
                seeder.seed(schoolId);
            } catch (DataIntegrityViolationException seededInParallel) {
                // fine
            }
            found = roles.findBySchoolIdAndCode(schoolId, roleCode);
        }
        return found;
    }

    private static String encode(PermissionMatrix matrix) {
        StringBuilder out = new StringBuilder();
        for (var pair : matrix.pairs()) {
            out.append(out.isEmpty() ? "" : ",").append(pair.getKey().key()).append(':').append(pair.getValue().key());
        }
        return out.isEmpty() ? "-" : out.toString();   // "-" = a role that grants nothing (an empty value is not stored)
    }

    private static PermissionMatrix decode(String text) {
        PermissionMatrix matrix = new PermissionMatrix();
        if (!"-".equals(text)) {
            for (String pair : text.split(",")) {
                String[] parts = pair.split(":");
                // a module or action no longer known (removed since it was cached) is left out
                PermissionModule.fromKey(parts[0]).ifPresent(m -> PermissionAction.fromKey(parts[1]).ifPresent(a -> matrix.grant(m, a)));
            }
        }
        return matrix;
    }
}
