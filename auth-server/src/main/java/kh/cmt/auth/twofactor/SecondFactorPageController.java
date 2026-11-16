package kh.cmt.auth.twofactor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import kh.cmt.auth.login.WebLoginPages;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * The browser/webview pages of the second login step: enrol an authenticator app, enter the 6-digit code,
 * or ask for an SMS code instead. Pages show stable codes ({@code data-code}); display text is the job of
 * the login UI. Wrong codes count towards the account lock, apart from wrong passwords.
 */
@Controller
class SecondFactorPageController {

    private final TwoFactorService twoFactor;
    private final AccountRepository accounts;
    private final SecondFactorSteps steps;
    private final WebLoginPages pages;

    SecondFactorPageController(TwoFactorService twoFactor, AccountRepository accounts, SecondFactorSteps steps, WebLoginPages pages) {
        this.twoFactor = twoFactor;
        this.accounts = accounts;
        this.steps = steps;
        this.pages = pages;
    }

    // ------------------------------------------------------------------ enrolment (first time)

    @GetMapping(value = "/login/otp/enroll", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String enrolPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccountClaims account = current();
        if (twoFactor.isEnrolled(account.id())) {
            response.sendRedirect("/login/otp");
            return null;
        }
        return enrolHtml(request, account, List.of());
    }

    @PostMapping(value = "/login/otp/enroll", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String enrol(HttpServletRequest request, HttpServletResponse response, @RequestParam String code)
            throws IOException {
        AccountClaims account = current();
        if (request.getSession().getAttribute(SecondFactorSteps.PENDING_SECRET) == null) {
            response.sendRedirect("/login/otp/enroll");
            return null;
        }
        if (steps.confirmEnrolment(request.getSession(), account.id(), code)) {
            return complete(request, response, account, false);
        }
        return failed(request, response, account, () -> enrolHtml(request, account, List.of("INVALID_CODE")));
    }

    // ------------------------------------------------------------------ code entry (every login)

    @GetMapping(value = "/login/otp", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String codePage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccountClaims account = current();
        if (!twoFactor.isEnrolled(account.id())) {
            response.sendRedirect("/login/otp/enroll");
            return null;
        }
        return codeHtml(request, account, "totp", List.of());
    }

    @PostMapping(value = "/login/otp", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String verify(HttpServletRequest request, HttpServletResponse response, @RequestParam String code,
            @RequestParam(defaultValue = "totp") String method) throws IOException {
        AccountClaims account = current();
        boolean sms = "sms".equals(method);
        boolean ok = sms ? twoFactor.verifySms(account.id(), code.trim()) : twoFactor.verifyTotp(account.id(), code.trim());
        if (ok) {
            return complete(request, response, account, sms);
        }
        return failed(request, response, account, () -> codeHtml(request, account, sms ? "sms" : "totp", List.of("INVALID_CODE")));
    }

    /** "Send me an SMS instead". */
    @PostMapping(value = "/login/otp/sms", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String sendSms(HttpServletRequest request) {
        AccountClaims account = current();
        var result = twoFactor.sendSms(account.id());
        if (result == TwoFactorService.SmsResult.SENT) {
            return codeHtml(request, account, "sms", List.of("SMS_SENT"));
        }
        return codeHtml(request, account, "totp", List.of(result.name()));
    }

    // ------------------------------------------------------------------ steps shared by the pages

    /** Wrong code: count it; on the limit the account is locked and the session ended. */
    private String failed(HttpServletRequest request, HttpServletResponse response, AccountClaims account,
            java.util.function.Supplier<String> page) throws IOException {
        if (steps.failed(request, response)) {
            pages.redirect(request, response, pages.error("LOCKED"));
            return null;
        }
        return page.get();
    }

    /** Second step done: mark the session (it becomes {@code amr} otp / sms) and resume the login. */
    private String complete(HttpServletRequest request, HttpServletResponse response, AccountClaims account,
            boolean viaSms) throws IOException {
        String next = steps.finish(request, response, viaSms);
        if (next != null) {
            response.sendRedirect(next);
            return null;
        }
        return page("<p>Verified. You can close this page.</p>");
    }

    private AccountClaims current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return accounts.findByUsername(authentication.getName()).orElseThrow();
    }

    // ------------------------------------------------------------------ HTML

    private String enrolHtml(HttpServletRequest request, AccountClaims account, List<String> messages) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SecondFactorSteps.Enrolment enrolment = steps.enrolment(request.getSession(), username);
        String secret = enrolment.secret();
        return page("<h2>Set up two-step verification</h2>"
                + "<p>Scan this code with an authenticator app (Google Authenticator, Microsoft Authenticator, Authy...), "
                + "then enter the 6-digit code it shows.</p>"
                + enrolment.qrSvg()
                + "<p>Cannot scan? Enter this key by hand: <code data-secret=\"" + esc(secret) + "\">" + esc(secret) + "</code></p>"
                + messages(messages)
                + form("/login/otp/enroll", request, "<input name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" "
                        + "maxlength=\"6\" placeholder=\"6-digit code\" required/>", "Turn on"));
    }

    private String codeHtml(HttpServletRequest request, AccountClaims account, String method, List<String> messages) {
        boolean sms = "sms".equals(method);
        String smsOption = "";
        if (!sms && twoFactor.hasPhone(account.id())) {
            smsOption = form("/login/otp/sms", request, "", "Send me an SMS code instead");
        }
        return page("<h2>Two-step verification</h2>"
                + "<p>" + (sms ? "Enter the code we sent by SMS." : "Enter the 6-digit code from your authenticator app.") + "</p>"
                + messages(messages)
                + form("/login/otp", request, "<input type=\"hidden\" name=\"method\" value=\"" + method + "\"/>"
                        + "<input name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"6\" "
                        + "placeholder=\"6-digit code\" required/>", "Verify")
                + smsOption);
    }

    private static String form(String action, HttpServletRequest request, String fields, String button) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return "<form method=\"post\" action=\"" + action + "\">"
                + "<input name=\"_csrf\" type=\"hidden\" value=\"" + esc(csrf.getToken()) + "\"/>"
                + "<p>" + fields + "</p><p><button type=\"submit\">" + esc(button) + "</button></p></form>";
    }

    private static String messages(List<String> codes) {
        if (codes.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<ul class=\"messages\">");
        codes.forEach(code -> html.append("<li data-code=\"").append(esc(code)).append("\">").append(esc(code)).append("</li>"));
        return html.append("</ul>").toString();
    }

    private static String page(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Two-step verification</title></head><body>" + body + "</body></html>";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
