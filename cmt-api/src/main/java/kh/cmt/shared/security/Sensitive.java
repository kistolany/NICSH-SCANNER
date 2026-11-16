package kh.cmt.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a value in a response (a record component or field of a view) as sensitive. Every JSON response then
 * writes {@link FieldMask#MASK} instead of the value when the caller's role may not see that group of data; nothing
 * has to be done in the controller or the mapper.
 * <pre>
 * public record PersonView(String name, &#64;Sensitive(SensitiveField.PHONE) String phone, ...)
 * </pre>
 * A {@code null} value stays {@code null}. Exports use {@link kh.cmt.shared.export.ExportColumn#sensitive} instead.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {

    SensitiveField value();
}
