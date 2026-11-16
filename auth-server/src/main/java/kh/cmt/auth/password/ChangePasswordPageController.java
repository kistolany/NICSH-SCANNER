package kh.cmt.auth.password;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import kh.cmt.auth.account.AccountRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * The "choose a new password" page shown in the browser/webview during login when the account has
 * {@code must_change_password} (first login with a generated password, or after an admin reset). After a
 * successful change the user continues to the authorization request they came from.
 */
@Controller
class ChangePasswordPageController {

    private final PasswordService passwords;
    private final PasswordPolicyService policy;
    private final AccountRepository accounts;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    ChangePasswordPageController(PasswordService passwords, PasswordPolicyService policy, AccountRepository accounts) {
        this.passwords = passwords;
        this.policy = policy;
        this.accounts = accounts;
    }

    @GetMapping(value = "/password/change", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String form(HttpServletRequest request) {
        return page(request, List.of());
    }

    @PostMapping(value = "/password/change", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String submit(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            @RequestParam String currentPassword, @RequestParam String newPassword,
            @RequestParam String confirmPassword) throws Exception {
        if (!newPassword.equals(confirmPassword)) {
            return page(request, List.of("PASSWORDS_DO_NOT_MATCH"));
        }
        try {
            var account = accounts.findByUsername(authentication.getName()).orElseThrow();
            passwords.change(account.id(), currentPassword, newPassword);
        } catch (PasswordException e) {
            Object violations = e.properties().get("violations");
            @SuppressWarnings("unchecked")
            List<String> codes = violations instanceof List<?> l ? (List<String>) l : List.of(e.code());
            return page(request, codes);
        }
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null) {
            requestCache.removeRequest(request, response);
            response.sendRedirect(saved.getRedirectUrl());
            return null;
        }
        return "<!DOCTYPE html><html><body><p>Password changed. You can close this page.</p></body></html>";
    }

    private String page(HttpServletRequest request, List<String> errors) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        var rules = policy.current();
        StringBuilder html = new StringBuilder("""
                <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Choose a new password</title></head><body>
                <h2>Choose a new password</h2>
                """);
        html.append("<p>At least ").append(rules.minLength()).append(" characters")
                .append(rules.requireUpper() ? ", an uppercase letter" : "")
                .append(rules.requireLower() ? ", a lowercase letter" : "")
                .append(rules.requireDigit() ? ", a number" : "")
                .append(rules.requireSymbol() ? ", a symbol" : "").append(".</p>");
        if (!errors.isEmpty()) {
            html.append("<ul class=\"errors\">");
            errors.forEach(code -> html.append("<li data-code=\"").append(HtmlUtils.htmlEscape(code)).append("\">")
                    .append(HtmlUtils.htmlEscape(code)).append("</li>"));
            html.append("</ul>");
        }
        html.append("<form method=\"post\" action=\"/password/change\">")
                .append("<input name=\"_csrf\" type=\"hidden\" value=\"").append(HtmlUtils.htmlEscape(csrf.getToken())).append("\"/>")
                .append("<p><input type=\"password\" name=\"currentPassword\" placeholder=\"Current password\" required/></p>")
                .append("<p><input type=\"password\" name=\"newPassword\" placeholder=\"New password\" required/></p>")
                .append("<p><input type=\"password\" name=\"confirmPassword\" placeholder=\"Repeat new password\" required/></p>")
                .append("<p><button type=\"submit\">Change password</button></p></form></body></html>");
        return html.toString();
    }
}
