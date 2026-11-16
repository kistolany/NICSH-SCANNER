package kh.cmt.iam.application;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.iam.infrastructure.RoleAccessStore;
import kh.cmt.iam.infrastructure.RoleAccessStore.StoredAccess;
import kh.cmt.shared.security.ScopeType;
import kh.cmt.shared.security.SensitiveField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The record scope and sensitive fields of a role, cached in Redis next to its permission table and under the same
 * permission version: {@code access:{school}:{role}:{version}} (the version key {@code perm:ver:{school}:{role}} is
 * the one {@link PermissionService} keeps). What is cached belongs to the version read together with it, and every
 * change raises the version, so a cached value is never stale. If Redis cannot be reached the database is read.
 */
@Component
class RoleAccessCache {

    private static final Logger log = LoggerFactory.getLogger(RoleAccessCache.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final RoleAccessStore store;
    private final StringRedisTemplate redis;

    RoleAccessCache(RoleAccessStore store, StringRedisTemplate redis) {
        this.store = store;
        this.redis = redis;
    }

    /** Empty if the school has no such active role. */
    Optional<StoredAccess> of(UUID schoolId, String roleCode) {
        if (roleCode == null || schoolId == null) {
            return Optional.empty();
        }
        try {
            String version = redis.opsForValue().get("perm:ver:" + schoolId + ":" + roleCode);
            if (version != null) {
                if ("0".equals(version)) {
                    return Optional.empty();   // known not to exist
                }
                String cached = redis.opsForValue().get(key(schoolId, roleCode, version));
                if (cached != null) {
                    return Optional.of(decode(Integer.parseInt(version), cached));
                }
            }
            Optional<StoredAccess> loaded = store.findActive(schoolId, roleCode);
            loaded.ifPresent(a -> redis.opsForValue().set(key(schoolId, roleCode, String.valueOf(a.permVersion())), encode(a), TTL));
            return loaded;
        } catch (RuntimeException redisProblem) {
            log.error("Role access cache unavailable, reading the database (role {})", roleCode, redisProblem);
            return store.findActive(schoolId, roleCode);
        }
    }

    private static String key(UUID schoolId, String roleCode, String version) {
        return "access:" + schoolId + ":" + roleCode + ":" + version;
    }

    /** SCOPE_TYPE|dept,dept|field,field */
    private static String encode(StoredAccess access) {
        return access.scopeType().name()
                + "|" + access.departmentIds().stream().map(UUID::toString).collect(Collectors.joining(","))
                + "|" + access.sensitive().stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static StoredAccess decode(int version, String text) {
        String[] parts = text.split("\\|", -1);
        Set<UUID> departments = parts[1].isEmpty() ? Set.of()
                : Arrays.stream(parts[1].split(",")).map(UUID::fromString).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<SensitiveField> sensitive = EnumSet.noneOf(SensitiveField.class);
        if (!parts[2].isEmpty()) {
            Arrays.stream(parts[2].split(",")).map(SensitiveField::valueOf).forEach(sensitive::add);
        }
        return new StoredAccess(version, ScopeType.valueOf(parts[0]), departments, sensitive);
    }
}
