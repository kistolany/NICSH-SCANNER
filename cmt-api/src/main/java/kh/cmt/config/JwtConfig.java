package kh.cmt.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kh.cmt.shared.security.SignOuts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Access-token validation. The keys are fetched from the auth server's JWKS (on first use, then cached and
 * refreshed when a new {@code kid} appears, so key rotation needs no restart). A token is accepted only if
 * it has a valid RS256 signature, is not expired, comes from the configured issuer, has a known {@code typ}, and its
 * user was not signed out everywhere after it was issued ({@link SignOuts}).
 */
@Configuration
class JwtConfig {

    /** Types of caller the auth server issues tokens for. */
    static final Set<String> KNOWN_TYPES = Set.of("app", "web", "job");

    @Bean
    JwtDecoder jwtDecoder(@Value("${cmt.security.jwk-set-uri}") String jwkSetUri,
            @Value("${cmt.security.issuer}") String issuer, SignOuts signOuts) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),   // 30 s of clock difference is tolerated
                new JwtIssuerValidator(issuer),
                knownTokenType(),
                notSignedOut(signOuts)));
        return decoder;
    }

    /** The user was signed out everywhere (deactivated, forced logout) after this token was issued. */
    private static OAuth2TokenValidator<Jwt> notSignedOut(SignOuts signOuts) {
        return jwt -> signOuts.signedOutSince(jwt.getSubject(), jwt.getIssuedAt())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Signed out", null))
                : OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidator<Jwt> knownTokenType() {
        return jwt -> jwt.getClaimAsString("typ") != null && KNOWN_TYPES.contains(jwt.getClaimAsString("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unknown token type", null));
    }

    /**
     * The token becomes an authentication named by {@code sub} with these authorities:
     * {@code TYPE_app | TYPE_web | TYPE_job} and {@code ROLE_<role>}.
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("TYPE_" + jwt.getClaimAsString("typ")));
            String role = jwt.getClaimAsString("role");
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return authorities;
        });
        return converter;
    }
}
