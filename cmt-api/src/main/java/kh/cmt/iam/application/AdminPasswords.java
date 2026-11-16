package kh.cmt.iam.application;

import java.util.List;
import java.util.UUID;
import kh.cmt.iam.domain.PasswordRules;
import kh.cmt.iam.infrastructure.AuthAccountStore;
import kh.cmt.iam.infrastructure.AuthAccountStore.PasswordPolicy;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Passwords are given by an admin, never sent by email or SMS (user decision, 2026-10-26): web users and app logins
 * alike. A typed password is checked against the school's password rules ({@code PASSWORD_POLICY} with the
 * violations); an empty one is generated and returned once, for the admin to hand over. Stored as a bcrypt hash, which
 * the auth server accepts and turns into Argon2id at the next good login.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AdminPasswords {

    private final AuthAccountStore accounts;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    /** The hash to store, and the password when it was generated (null when the admin typed it). */
    record Chosen(String hash, String generated) {

        boolean wasGenerated() {
            return generated != null;
        }
    }

    Chosen choose(UUID school, String username, String typed) {
        PasswordPolicy p = accounts.passwordPolicy(school);
        PasswordRules rules = new PasswordRules(p.minLength(), p.upper(), p.lower(), p.digit(), p.symbol());
        if (typed == null || typed.isEmpty()) {
            String generated = rules.generate(username);
            return new Chosen(hash(generated), generated);
        }
        List<String> violations = rules.violations(username, typed);
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY).with("violations", violations);
        }
        return new Chosen(hash(typed), null);
    }

    private String hash(String password) {
        return "{bcrypt}" + bcrypt.encode(password);
    }
}
