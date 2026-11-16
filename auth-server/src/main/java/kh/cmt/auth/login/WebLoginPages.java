package kh.cmt.auth.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import kh.cmt.auth.AuthProperties;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Where each sign-in step is shown: the admin web's own screens ({@code auth.web.login-url}, steps as
 * {@code ?step=new-password|otp|enroll|denied}, refusals as {@code ?error=CODE}) or, when that is blank, this server's
 * plain pages ({@code /login}, {@code /password/change}, {@code /login/otp}…).
 */
@Component
public class WebLoginPages {

    private final String loginUrl;
    private final List<String> allowedOrigins;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public WebLoginPages(AuthProperties props) {
        AuthProperties.Web web = props.web();
        this.loginUrl = web == null || !StringUtils.hasText(web.loginUrl()) ? "" : web.loginUrl().trim();
        this.allowedOrigins = web == null || web.allowedOrigins() == null ? List.of()
                : web.allowedOrigins().stream().filter(StringUtils::hasText).map(String::trim).toList();
    }

    /** True when the admin web shows the sign-in screens. */
    public boolean external() {
        return !loginUrl.isEmpty();
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    public String login() {
        return external() ? loginUrl : "/login";
    }

    /** {@code step} new-password | otp | enroll. */
    public String step(String step) {
        if (external()) {
            return loginUrl + "?step=" + step;
        }
        return switch (step) {
            case "new-password" -> "/password/change";
            case "otp" -> "/login/otp";
            case "enroll" -> "/login/otp/enroll";
            default -> "/login";
        };
    }

    public String denied(String reason) {
        return external() ? loginUrl + "?step=denied&reason=" + reason : "/login/denied?reason=" + reason;
    }

    /** A refused password login: {@code code} BAD_CREDENTIALS | LOCKED | DISABLED | ACCESS_EXPIRED. */
    public String error(String code) {
        return external() ? loginUrl + "?error=" + code : "/login?error";
    }

    public void redirect(HttpServletRequest request, HttpServletResponse response, String target) throws IOException {
        response.sendRedirect(target.startsWith("http") ? target : request.getContextPath() + target);
    }

    /** The authorization request the login interrupted (to resume it), taken out of the session; null when none. */
    public String takeSavedRequest(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved == null) {
            return null;
        }
        requestCache.removeRequest(request, response);
        return saved.getRedirectUrl();
    }
}
