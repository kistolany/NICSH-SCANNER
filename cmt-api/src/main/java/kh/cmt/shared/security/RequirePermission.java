package kh.cmt.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on a use case: the caller must hold this action in this module, otherwise the call is refused with
 * {@code 403 FORBIDDEN} before the method runs. Example:
 * <pre>{@code
 * @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
 * public PersonView update(UUID id, PersonCommand command) { ... }
 * }</pre>
 * On a class it applies to every public method that has no annotation of its own.
 * <p>
 * Works on calls that come from outside the bean (a controller calling a service). A method calling another
 * method of the same object is not checked. When the module depends on the data (for example a list whose
 * module depends on its kind), call {@link PermissionGuard#require} in the method instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {

    PermissionModule module();

    PermissionAction action();
}
