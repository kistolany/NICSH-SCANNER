package kh.cmt.org.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgLabel;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.org.infrastructure.OrgItemRepository;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.domain.SoftDeletable;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import kh.cmt.shared.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * The work for one kind of item (department, position...). Subclasses only say what is different: which
 * fields exist, how they are cleaned, how names are compared. Called inside the transaction of
 * {@link OrgItemService}.
 *
 * @param <E> the entity
 */
abstract class OrgItemType<E extends SoftDeletable> {

    private final OrgKind kind;
    private final OrgItemRepository<E> repository;
    protected final OrgSupport support;

    protected OrgItemType(OrgKind kind, OrgItemRepository<E> repository, OrgSupport support) {
        this.kind = kind;
        this.repository = repository;
        this.support = support;
    }

    OrgKind kind() {
        return kind;
    }

    // ------------------------------------------------------------------ what differs per kind

    protected abstract E newInstance();

    /** Trims and checks the input; throws VALIDATION_ERROR listing every problem. Returns the cleaned command. */
    protected abstract OrgItemCommand normalize(OrgItemCommand command);

    protected abstract void apply(E entity, OrgItemCommand cleaned);

    protected abstract OrgItemView toView(E entity);

    /** The text that must be unique among active items, lower-cased ("sports", "9a"). */
    protected abstract String nameKey(OrgItemCommand cleaned);

    protected abstract String nameKeyOf(E entity);

    protected abstract Specification<E> hasNameKey(String key);

    protected abstract Specification<E> matches(String searchText);

    protected abstract Sort defaultSort();

    // ------------------------------------------------------------------ operations

    PageResponse<OrgItemView> list(boolean inactive, String q, int page, int size) {
        if (inactive) {
            SoftDeleteFilter.includeInactive(support.entityManager());   // the Inactive tab asks for them
        }
        Specification<E> spec = inSchool().and((root, query, cb) -> cb.equal(root.get("inactive"), inactive));
        if (q != null && !q.isBlank()) {
            spec = spec.and(matches(q.trim()));
        }
        Page<E> found = repository.findAll(spec, PageRequest.of(page, size, defaultSort()));
        Map<UUID, Long> people = peopleCounts();
        return PageResponse.of(found, e -> withPeople(toView(e), people));
    }

    OrgItemView get(UUID id) {
        return withPeople(toView(load(id)), peopleCounts());
    }

    /** Names / labels of these items of the school (inactive ones too); unknown ids are left out. */
    Map<UUID, OrgLabel> labels(Collection<UUID> ids) {
        Map<UUID, OrgLabel> found = new HashMap<>();
        UUID school = support.schoolId();
        for (UUID id : new HashSet<>(ids)) {
            if (id != null) {
                repository.findById(id).filter(e -> e.getSchoolId().equals(school)).map(this::toView)
                        .ifPresent(v -> found.put(id, label(v)));
            }
        }
        return found;
    }

    /** The active items of the school, in the list's order. */
    List<OrgLabel> active() {
        return repository.findAll(inSchool(), defaultSort()).stream().map(this::toView).map(OrgItemType::label).toList();
    }

    private static OrgLabel label(OrgItemView v) {
        return new OrgLabel(v.id(), v.name() == null ? null : new LocalizedName(v.name().km(), v.name().en(), v.name().ar()),
                v.color(), v.inactive());
    }

    /** The active item called {@code name} (any of its names, ignoring case). */
    java.util.Optional<UUID> activeByName(String name) {
        Specification<E> spec = inSchool().and(namedExactly(name.toLowerCase()));   // active only: the filter is on
        return repository.findAll(spec).stream().findFirst().map(SoftDeletable::getId);
    }

    List<String> activeNames() {
        return repository.findAll(inSchool(), defaultSort()).stream()
                .map(e -> { OrgItemView v = toView(e); return v.name() == null ? null : v.name().en(); })
                .toList();
    }

    /** Matches the name that must be unique; kinds with names in several languages also match the others. */
    protected Specification<E> namedExactly(String lowerName) {
        return hasNameKey(lowerName);
    }

    /** Active people per item, for kinds people have (departments, positions); null for the others. */
    protected Map<UUID, Long> peopleCounts() {
        return null;
    }

    private static OrgItemView withPeople(OrgItemView view, Map<UUID, Long> counts) {
        return counts == null ? view : view.withPeople(counts.getOrDefault(view.id(), 0L));
    }

    OrgItemView create(OrgItemCommand command) {
        OrgItemCommand cleaned = normalize(command);
        ensureNameFree(nameKey(cleaned), null);
        E entity = newInstance();
        apply(entity, cleaned);
        return toView(saveAndFlush(entity));
    }

    OrgItemView update(UUID id, OrgItemCommand command) {
        E entity = load(id);
        OrgItemCommand cleaned = normalize(command);
        if (command.version() == null) {
            throw validation(List.of(Map.of("field", "version", "rule", "NotNull")));
        }
        if (command.version() != entity.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);   // somebody else changed it first
        }
        // check before changing the entity: a changed entity is flushed (and could hit the unique index) by any query
        ensureNameFree(nameKey(cleaned), id);
        apply(entity, cleaned);
        return toView(saveAndFlush(entity));
    }

    void inactivate(UUID id, UUID by, String reason) {
        E entity = load(id);
        if (!entity.isInactive()) {
            String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
            entity.inactivate(by, support.clock().instant(), cleanReason);
        }
    }

    OrgItemView restore(UUID id) {
        E entity = load(id);
        if (entity.isInactive()) {
            ensureNameFree(nameKeyOf(entity), id);   // the name may have been taken again meanwhile
            entity.restore();
        }
        return toView(saveAndFlush(entity));
    }

    void deleteForever(UUID id) {
        E entity = load(id);
        if (!entity.isInactive()) {
            throw new ApiException(ErrorCode.NOT_INACTIVE);
        }
        try {
            repository.delete(entity);
            repository.flush();
        } catch (DataIntegrityViolationException stillReferenced) {
            throw new ApiException(ErrorCode.IN_USE);   // old records (people...) still point to it
        }
    }

    boolean isActive(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getSchoolId().equals(support.schoolId()))
                .map(e -> !e.isInactive())
                .orElse(false);
    }

    // ------------------------------------------------------------------ helpers

    /** Finds an item of the caller's school, active or inactive. */
    private E load(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getSchoolId().equals(support.schoolId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void ensureNameFree(String key, UUID exceptId) {
        Specification<E> sameName = inSchool().and(hasNameKey(key));   // active items only: the inactive filter is on
        if (exceptId != null) {
            sameName = sameName.and((root, query, cb) -> cb.notEqual(root.get("id"), exceptId));
        }
        if (repository.exists(sameName)) {
            throw new ApiException(ErrorCode.NAME_TAKEN);
        }
    }

    private E saveAndFlush(E entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.NAME_TAKEN);   // lost a race with a parallel request: the unique index caught it
        }
    }

    private Specification<E> inSchool() {
        UUID school = support.schoolId();
        return (root, query, cb) -> cb.equal(root.get("schoolId"), school);
    }

    // ------------------------------------------------------------------ for subclasses

    protected static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }

    /** Trims, collapses inner spaces; empty becomes null. */
    protected static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    protected static List<Map<String, String>> newErrors() {
        return new ArrayList<>();
    }

    protected static String escapeLike(String text) {
        return text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
