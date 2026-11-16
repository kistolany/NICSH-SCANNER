package kh.cmt.shared.security;

import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The school of the signed-in caller, from the {@code school_id} claim of the access token. Work that runs
 * without a caller uses the school it was started for ({@link SchoolOverride}, e.g. the outbox relay), else the
 * configured school (start-up).
 */
@Component
@Primary
class JwtSchoolContext implements SchoolContext {

    private final ConfiguredSchoolContext fallback;

    JwtSchoolContext(ConfiguredSchoolContext fallback) {
        this.fallback = fallback;
    }

    @Override
    public UUID currentSchoolId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            String claim = token.getToken().getClaimAsString("school_id");
            if (claim == null) {
                throw new IllegalStateException("The access token has no school_id claim");
            }
            return UUID.fromString(claim);
        }
        UUID override = SchoolOverride.current();
        return override != null ? override : fallback.currentSchoolId();
    }
}
