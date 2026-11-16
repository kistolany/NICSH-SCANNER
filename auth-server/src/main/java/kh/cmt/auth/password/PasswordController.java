package kh.cmt.auth.password;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /auth/password/change} and {@code /auth/password/policy} (bearer token; the admin web reaches them through the
 * BFF as {@code /bff/password/**}). No forgot / reset: an admin gives a new password.
 */
@RestController
@RequestMapping("/auth/password")
class PasswordController {

    record ChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    /** The same shape as the sign-in screens' {@code rules} ({@code /login/api/state}). */
    record Rules(int minLength, boolean upper, boolean lower, boolean digit, boolean symbol) {
    }

    private final PasswordService passwords;
    private final PasswordPolicyService policy;

    PasswordController(PasswordService passwords, PasswordPolicyService policy) {
        this.passwords = passwords;
        this.policy = policy;
    }

    /** The school's rules for a new password, so the "Change password" screen can tick them while typing. */
    @GetMapping("/policy")
    Rules policy() {
        PasswordPolicyService.Policy p = policy.current();
        return new Rules(p.minLength(), p.requireUpper(), p.requireLower(), p.requireDigit(), p.requireSymbol());
    }

    @PostMapping("/change")
    ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeRequest request) {
        String type = jwt.getClaimAsString("typ");
        if (!"app".equals(type) && !"web".equals(type)) {
            throw new PasswordException(PasswordException.FORBIDDEN, HttpStatus.FORBIDDEN);
        }
        passwords.change(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
