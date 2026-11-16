package kh.cmt.auth.login;

import java.util.Arrays;
import kh.cmt.auth.login.LoginRuleService.Refusal;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Shown when the role's login rules refuse a sign-in (see {@link LoginRulesFilter}). Like the other login pages it
 * carries a stable code ({@code data-code}: ACCESS_EXPIRED, DAY_NOT_ALLOWED, OUTSIDE_HOURS, IP_NOT_ALLOWED); display
 * text is the job of the login UI.
 */
@Controller
class LoginDeniedPageController {

    @GetMapping(value = "/login/denied", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String denied(@RequestParam(required = false) String reason) {
        // only known codes are echoed back, so the page cannot be made to show arbitrary text
        String code = Arrays.stream(Refusal.values()).map(Enum::name).filter(name -> name.equals(reason))
                .findFirst().orElse("LOGIN_NOT_ALLOWED");
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Sign-in not allowed</title></head><body>"
                + "<h2>Sign-in not allowed</h2>"
                + "<p>Your access has ended, or your role does not allow signing in at this time or from this place.</p>"
                + "<ul class=\"messages\"><li data-code=\"" + code + "\">" + code + "</li></ul>"
                + "<p><a href=\"/login\">Back to sign-in</a></p>"
                + "</body></html>";
    }
}
