package kh.cmt.shared.security;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The one place that decides whether the caller sees a sensitive value. Used by
 * <ul>
 *   <li>every JSON response, for values marked {@link Sensitive} (automatic)</li>
 *   <li>exporters, which leave out the columns the caller may not see ({@link #visibleColumns})</li>
 *   <li>updates: a value the caller cannot see is kept as it is, so sending the mask back never overwrites it
 *       ({@link #keepIfHidden})</li>
 * </ul>
 */
@Component
public class FieldMask {

    /** What the caller sees instead of a hidden value. */
    public static final String MASK = "••••";

    private static final String REQUEST_KEY = FieldMask.class.getName() + ".fields";

    private final ObjectProvider<FieldAccess> access;

    FieldMask(ObjectProvider<FieldAccess> access) {
        this.access = access;
    }

    /** May the current caller see this group? Yes when nobody is signed in (jobs, start-up). */
    public boolean allows(SensitiveField field) {
        return visibleFields().contains(field);
    }

    /** Worked out once per request (a list of people asks for every row). */
    public Set<SensitiveField> visibleFields() {
        Optional<CurrentUser> user = CurrentUser.current();
        if (user.isEmpty()) {
            return EnumSet.allOf(SensitiveField.class);
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST) instanceof Remembered known
                && known.user().equals(user.get())) {
            return known.fields();
        }
        Set<SensitiveField> fields = Set.copyOf(access.getObject().visibleFields(user.get()));
        if (request != null) {
            request.setAttribute(REQUEST_KEY, new Remembered(user.get(), fields), RequestAttributes.SCOPE_REQUEST);
        }
        return fields;
    }

    private record Remembered(CurrentUser user, Set<SensitiveField> fields) {
    }

    /** The value, or {@link #MASK} when the caller may not see it ({@code null} stays {@code null}). */
    public Object mask(SensitiveField field, Object value) {
        return value == null || allows(field) ? value : MASK;
    }

    /** For updates: what the caller sent if they may see the field, otherwise what was stored before. */
    public <T> T keepIfHidden(SensitiveField field, T sent, T stored) {
        return allows(field) ? sent : stored;
    }

    /** The columns of an export the caller may see, in the same order. */
    public <C> List<C> visibleColumns(List<C> columns, Function<C, SensitiveField> sensitivityOf) {
        Set<SensitiveField> visible = visibleFields();
        return columns.stream().filter(c -> {
            SensitiveField field = sensitivityOf.apply(c);
            return field == null || visible.contains(field);
        }).toList();
    }
}
