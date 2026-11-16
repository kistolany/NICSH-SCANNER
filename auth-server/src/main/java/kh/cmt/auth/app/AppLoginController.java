package kh.cmt.auth.app;

import java.net.URI;
import java.util.UUID;
import kh.cmt.auth.app.AppLoginService.LoginRequest;
import kh.cmt.auth.app.AppLoginService.LoginResponse;
import kh.cmt.auth.app.AppLoginService.Tokens;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mobile app's login (JSON, camelCase).
 * <pre>
 * POST /auth/login    {username, password, deviceId, deviceName, platform, appVersion}
 *                     → {accessToken, refreshToken, tokenType, expiresIn, role, mustChangePassword}
 * POST /auth/refresh  {refreshToken} → {accessToken, refreshToken, tokenType, expiresIn}
 * POST /auth/logout   (bearer access token) → 204
 * </pre>
 * Errors are problem JSON with {@code code}: INVALID_CREDENTIALS (401), ACCOUNT_LOCKED / ACCOUNT_INACTIVE /
 * ACCESS_EXPIRED / NOT_AN_APP_ACCOUNT (403), DEVICE_MISMATCH (403, with {@code linkedDeviceName},
 * {@code deviceChangeToken}), INVALID_TOKEN (401), VALIDATION_ERROR (400, {@code errors [{field, rule}]}).
 */
@RestController
@RequestMapping("/auth")
class AppLoginController {

    record RefreshRequest(String refreshToken) {
    }

    private final AppLoginService service;

    AppLoginController(AppLoginService service) {
        this.service = service;
    }

    @PostMapping("/login")
    LoginResponse login(@RequestBody LoginRequest request) {
        return service.login(request);
    }

    @PostMapping("/refresh")
    Tokens refresh(@RequestBody RefreshRequest request) {
        return service.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        if ("app".equals(jwt.getClaimAsString("typ"))) {
            service.logout(UUID.fromString(jwt.getSubject()));
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AppLoginException.class)
    ResponseEntity<ProblemDetail> refused(AppLoginException ex) {
        ProblemDetail problem = problem(ex.status(), ex.code());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status()).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, AppLoginException.VALIDATION_ERROR));
    }

    private static ProblemDetail problem(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("urn:cmt:error:" + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
