package kh.cmt.iam.application;

import kh.cmt.shared.security.PermissionGuard;
import kh.cmt.shared.security.RequirePermission;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequirePermission}. Runs before the transaction of the use case is opened (a refused call
 * costs no database work). A method-level annotation wins over a class-level one.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PermissionAspect {

    private final PermissionGuard guard;

    PermissionAspect(PermissionGuard guard) {
        this.guard = guard;
    }

    @Before("@annotation(permission)")
    void onMethod(RequirePermission permission) {
        guard.require(permission.module(), permission.action());
    }

    @Before("@within(permission) && !@annotation(kh.cmt.shared.security.RequirePermission) && execution(public * *(..))")
    void onClass(RequirePermission permission) {
        guard.require(permission.module(), permission.action());
    }
}
