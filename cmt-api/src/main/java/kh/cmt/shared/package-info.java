/**
 * Shared kernel: base entity, soft delete, error format, current user, localized text. Every module may use
 * anything in here, so the module is open (its sub-packages are not "internal" to Spring Modulith).
 * Keep it small: only truly shared things belong here.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared kernel")
package kh.cmt.shared;

import org.springframework.modulith.ApplicationModule;
