package kh.cmt.org.application;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.application.OrgItemCommand.OrgName;
import kh.cmt.org.domain.Colored;
import kh.cmt.org.domain.LocalizedOrgItem;
import kh.cmt.org.infrastructure.OrgItemRepository;
import kh.cmt.shared.i18n.LocalizedText;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Departments and positions: a name in Khmer / English / Arabic (English required), and for departments also a
 * colour.
 */
abstract class LocalizedItemType<E extends LocalizedOrgItem> extends OrgItemType<E> {

    static final String DEFAULT_COLOR = "#4B5563";
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final boolean hasColor;

    protected LocalizedItemType(OrgKind kind, OrgItemRepository<E> repository, OrgSupport support, boolean hasColor) {
        super(kind, repository, support);
        this.hasColor = hasColor;
    }

    @Override
    protected OrgItemCommand normalize(OrgItemCommand command) {
        List<Map<String, String>> errors = newErrors();
        OrgName in = command.name();
        String km = in == null ? null : clean(in.km());
        String en = in == null ? null : clean(in.en());
        String ar = in == null ? null : clean(in.ar());
        if (en == null) {
            errors.add(Map.of("field", "name.en", "rule", "NotBlank"));
        }
        for (String[] named : new String[][] {{"name.km", km}, {"name.en", en}, {"name.ar", ar}}) {
            if (named[1] != null && named[1].length() > 100) {
                errors.add(Map.of("field", named[0], "rule", "Size"));
            }
        }
        String color = null;
        if (hasColor) {
            color = clean(command.color());
            if (color == null) {
                color = DEFAULT_COLOR;
            } else if (!COLOR.matcher(color).matches()) {
                errors.add(Map.of("field", "color", "rule", "Pattern"));
            } else {
                color = color.toUpperCase();
            }
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return new OrgItemCommand(new OrgName(km, en, ar), color, command.version());
    }

    @Override
    protected void apply(E entity, OrgItemCommand cleaned) {
        entity.setName(new LocalizedText(cleaned.name().km(), cleaned.name().en(), cleaned.name().ar()));
        if (hasColor) {
            ((Colored) entity).setColor(cleaned.color());
        }
    }

    @Override
    protected OrgItemView toView(E entity) {
        LocalizedText name = entity.getName();
        String color = hasColor ? ((Colored) entity).getColor() : null;
        return new OrgItemView(entity.getId(), new OrgName(name.getKm(), name.getEn(), name.getAr()), color,
                entity.isInactive(), entity.getDeletedAt(), entity.getDeletedBy(), entity.getDeleteReason(),
                entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt(), null);
    }

    @Override
    protected String nameKey(OrgItemCommand cleaned) {
        return cleaned.name().en().toLowerCase();
    }

    @Override
    protected String nameKeyOf(E entity) {
        return entity.getName().getEn().toLowerCase();
    }

    @Override
    protected Specification<E> hasNameKey(String key) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("name").get("en")), key);
    }

    @Override
    protected Specification<E> namedExactly(String lowerName) {
        return (root, query, cb) -> cb.or(cb.equal(cb.lower(root.get("name").get("en")), lowerName),
                cb.equal(cb.lower(root.get("name").get("km")), lowerName));
    }

    @Override
    protected Specification<E> matches(String searchText) {
        String pattern = "%" + escapeLike(searchText) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name").get("en")), pattern, '\\'),
                cb.like(cb.lower(root.get("name").get("km")), pattern, '\\'),
                cb.like(cb.lower(root.get("name").get("ar")), pattern, '\\'));
    }

    @Override
    protected Sort defaultSort() {
        return Sort.by(Sort.Order.asc("name.en").ignoreCase(), Sort.Order.asc("id"));
    }
}
