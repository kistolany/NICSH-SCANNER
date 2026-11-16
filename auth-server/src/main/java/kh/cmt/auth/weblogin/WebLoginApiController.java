package kh.cmt.auth.weblogin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import kh.cmt.auth.login.WebLoginPages;
import kh.cmt.auth.password.PasswordException;
import kh.cmt.auth.password.PasswordPolicyService;
import kh.cmt.auth.password.PasswordService;
import kh.cmt.auth.twofactor.SecondFactorFilter;
import kh.cmt.auth.twofactor.SecondFactorSteps;
import kh.cmt.auth.twofactor.TwoFactorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The JSON side of the admin web's own sign-in screens (login, 2-step code, new password). The browser stays on the
 * admin web; this server keeps the login session (its cookie) and resumes the authorization request when done.
 * <pre>
 * GET  /login/api/state        {csrf {headerName, token}, step login|new-password|otp|enroll|done, username,
 *                               rules {minLength, upper, lower, digit, symbol}, sms, enrolment {qrSvg, secret}, next}
 * POST /login                  (Spring) username + password, Accept: application/json → {next} | 401 {error}
 * POST /login/api/cancel       ends the half-done login ("Back")
 * POST /login/api/password     {newPassword, confirmPassword} → {next} | 400 {errors}
 * POST /login/api/otp          {code, method totp|sms} → {next} | 400 {errors [INVALID_CODE]} | 401 {errors [LOCKED]}
 * POST /login/api/otp/enroll   {code} → {next} | 400 {errors} | 401 {errors [LOCKED]}
 * POST /login/api/otp/sms      → {sent: true} | 400 {errors [NO_PHONE|TOO_SOON]}
 * </pre>
 * {@code next}: the address to open to carry on (null: start the sign-in again from the admin web).
 */
@RestController
class WebLoginApiController {

    private final AccountRepository accounts;
    private final TwoFactorService twoFactor;
    private final SecondFactorSteps steps;
    private final PasswordService passwords;
    private final PasswordPolicyService policy;
    private final WebLoginPages pages;

    WebLoginApiController(AccountRepository accounts, TwoFactorService twoFactor, SecondFactorSteps steps,
            PasswordService passwords, PasswordPolicyService policy, WebLoginPages pages) {
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.steps = steps;
        this.passwords = passwords;
        this.policy = policy;
        this.pages = pages;
    }

    /** The sign-in page lives in the admin web; this server's own address only points there. */
    @GetMapping("/login")
    void login(HttpServletResponse response) throws IOException {
        response.sendRedirect(pages.login());
    }

    @GetMapping("/login/api/state")
    Map<String, Object> state(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        Map<String, Object> out = new HashMap<>();
        out.put("csrf", Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken()));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AccountClaims account = signedIn(auth) ? accounts.findByUsername(auth.getName()).orElse(null) : null;
        if (account == null) {
            out.put("step", "login");
            return out;
        }
        out.put("username", auth.getName());
        if (accounts.mustChangePassword(auth.getName())) {
            var rules = policy.current();
            out.put("step", "new-password");
            out.put("rules", Map.of("minLength", rules.minLength(), "upper", rules.requireUpper(), "lower", rules.requireLower(),
                    "digit", rules.requireDigit(), "symbol", rules.requireSymbol()));
        } else if (!SecondFactorFilter.hasSecondFactor(auth) && twoFactor.requiredFor(account)) {
            boolean enrolled = twoFactor.isEnrolled(account.id());
            out.put("step", enrolled ? "otp" : "enroll");
            out.put("sms", enrolled && twoFactor.hasPhone(account.id()));
            if (!enrolled) {
                out.put("enrolment", steps.enrolment(request.getSession(), auth.getName()));
            }
        } else {
            out.put("step", "done");
            out.put("next", pages.takeSavedRequest(request, response));
        }
        return out;
    }

    @PostMapping("/login/api/cancel")
    Map<String, Object> cancel(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return Map.of("ok", true);
    }

    record NewPassword(String newPassword, String confirmPassword) {
    }

    @PostMapping("/login/api/password")
    ResponseEntity<Map<String, Object>> password(@RequestBody NewPassword body, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = requireSignedIn();
        String pw = body.newPassword() == null ? "" : body.newPassword();
        if (!pw.equals(body.confirmPassword())) {
            return errors(HttpStatus.BAD_REQUEST, List.of("PASSWORDS_DO_NOT_MATCH"));
        }
        try {
            passwords.changeAfterLogin(auth.getName(), pw);
        } catch (PasswordException e) {
            Object violations = e.properties().get("violations");
            @SuppressWarnings("unchecked")
            List<String> codes = violations instanceof List<?> l ? (List<String>) l : List.of(e.code());
            return errors(e.status() == HttpStatus.FORBIDDEN ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST, codes);
        }
        return next(pages.takeSavedRequest(request, response));
    }

    record Code(String code, String method) {
    }

    @PostMapping("/login/api/otp")
    ResponseEntity<Map<String, Object>> otp(@RequestBody Code body, HttpServletRequest request, HttpServletResponse response) {
        AccountClaims account = account(requireSignedIn());
        boolean sms = "sms".equals(body.method());
        String code = body.code() == null ? "" : body.code().trim();
        boolean ok = sms ? twoFactor.verifySms(account.id(), code) : twoFactor.verifyTotp(account.id(), code);
        if (ok) {
            return next(steps.finish(request, response, sms));
        }
        return wrongCode(request, response);
    }

    @PostMapping("/login/api/otp/enroll")
    ResponseEntity<Map<String, Object>> enroll(@RequestBody Code body, HttpServletRequest request, HttpServletResponse response) {
        AccountClaims account = account(requireSignedIn());
        if (steps.confirmEnrolment(request.getSession(), account.id(), body.code() == null ? "" : body.code())) {
            return next(steps.finish(request, response, false));
        }
        return wrongCode(request, response);
    }

    @PostMapping("/login/api/otp/sms")
    ResponseEntity<Map<String, Object>> sms() {
        AccountClaims account = account(requireSignedIn());
        TwoFactorService.SmsResult result = twoFactor.sendSms(account.id());
        return result == TwoFactorService.SmsResult.SENT ? ResponseEntity.ok(Map.of("sent", true))
                : errors(HttpStatus.BAD_REQUEST, List.of(result.name()));
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<Map<String, Object>> wrongCode(HttpServletRequest request, HttpServletResponse response) {
        return steps.failed(request, response) ? errors(HttpStatus.UNAUTHORIZED, List.of("LOCKED"))
                : errors(HttpStatus.BAD_REQUEST, List.of("INVALID_CODE"));
    }

    private static ResponseEntity<Map<String, Object>> next(String next) {
        Map<String, Object> body = new HashMap<>();
        body.put("next", next);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> errors(HttpStatus status, List<String> codes) {
        return ResponseEntity.status(status).body(Map.of("errors", codes));
    }

    private static boolean signedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private static Authentication requireSignedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!signedIn(auth)) {
            throw new NotSignedIn();
        }
        return auth;
    }

    private AccountClaims account(Authentication auth) {
        return accounts.findByUsername(auth.getName()).orElseThrow(NotSignedIn::new);
    }

    /** The login session is gone (expired, or ended by a lock): the screen starts again. */
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class NotSignedIn extends RuntimeException {
    }
}
