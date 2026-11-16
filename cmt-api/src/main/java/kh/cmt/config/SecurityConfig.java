package kh.cmt.config;

import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import java.util.List;
import kh.cmt.shared.web.ErrorCode;
import kh.cmtlib.ratelimit.KeyBy;
import kh.cmtlib.ratelimit.RateLimitFilter;
import kh.cmtlib.ratelimit.RateLimitRule;
import kh.cmtlib.ratelimit.RedisRateLimiter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/**
 * Who may call what. Every request needs a valid access token from the auth server, except health and the
 * API docs. Route groups (by the token's {@code typ}):
 * <pre>
 * /api/v1/app/**              app users (mobile)
 * /api/v1/admin/approvals/**  office users, and app users with role admin (the admin app's approvals inbox)
 * /api/v1/admin/**            office users (admin web); app admins too for approvals and, read only, people
 *                             (list, one person) and attendance day / month (the admin app's Staff tab)
 * /api/v1/files/**            office users and app users (uploads, download links)
 * GET /api/v1/public/**       everybody, no sign-in (school support contact)
 * anything else               denied
 * </pre>
 * Finer rules (module x action permissions, record scope, field masking) are added per use case in phase 3.
 */
@Configuration
class SecurityConfig {

    /** An office user, or an admin of the mobile admin app. */
    private static org.springframework.security.authorization.AuthorizationManager<
            org.springframework.security.web.access.intercept.RequestAuthorizationContext> officeOrAppAdmin() {
        return AuthorizationManagers.anyOf(hasAuthority("TYPE_web"),
                AuthorizationManagers.allOf(hasAuthority("TYPE_app"), hasAuthority("ROLE_admin")));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
            Converter<Jwt, AbstractAuthenticationToken> converter, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler, RedisRateLimiter limiter, RateLimitProperties limits)
            throws Exception {
        if (limits.enabled()) {
            // Not a bean: Spring Boot would also register a bean-Filter as a plain servlet filter.
            // Placed after the token is read, so the limit is per user, before any rule is evaluated.
            http.addFilterAfter(rateLimitFilter(limiter, limits), BearerTokenAuthenticationFilter.class);
        }
        return http
                .csrf(AbstractHttpConfigurer::disable)   // bearer tokens in a header, no cookies on this API
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // the few things the app needs before sign-in (school contact for the login screen)
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/public/**").permitAll()
                        .requestMatchers("/api/v1/app/**").access(hasAuthority("TYPE_app"))
                        .requestMatchers("/api/v1/admin/approvals/**").access(officeOrAppAdmin())
                        // the admin app's Staff tab and School today: read only (the module permissions of the app
                        // admin allow People view and Attendance view; /people/* is one person, not /pay, /documents)
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/admin/people",
                                "/api/v1/admin/people/*", "/api/v1/admin/attendance/day", "/api/v1/admin/attendance/month")
                                .access(officeOrAppAdmin())
                        .requestMatchers("/api/v1/admin/**").access(hasAuthority("TYPE_web"))
                        // uploads and downloads for both apps (what may be uploaded / read is decided in the files module)
                        .requestMatchers("/api/v1/files/**").access(AuthorizationManagers.anyOf(
                                hasAuthority("TYPE_web"), hasAuthority("TYPE_app")))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * 300 requests per minute per user for the API; the scan endpoints additionally 20 per minute per user.
     * A caller without a valid token is counted per IP instead.
     */
    static RateLimitFilter rateLimitFilter(RedisRateLimiter limiter, RateLimitProperties limits) {
        List<RateLimitRule> rules = List.of(
                RateLimitRule.of("api", KeyBy.USER, limits.general().capacity(), limits.general().window(), "/api/**"),
                RateLimitRule.of("scan", KeyBy.USER, limits.scan().capacity(), limits.scan().window(),
                        "/api/v1/app/attendance/scan", "/api/v1/app/attendance/scan/**"));
        return new RateLimitFilter(limiter, rules, request -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication instanceof JwtAuthenticationToken token ? token.getName() : null;
        });
    }

    /** 401: no token, or an invalid / expired one. Problem JSON with code UNAUTHORIZED. */
    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper mapper) {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(mapper, response, ErrorCode.UNAUTHORIZED);
        };
    }

    /** 403: valid token, wrong kind of caller. Problem JSON with code FORBIDDEN. */
    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper mapper) {
        return (request, response, exception) -> write(mapper, response, ErrorCode.FORBIDDEN);
    }

    private static void write(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse response, ErrorCode code)
            throws java.io.IOException {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
