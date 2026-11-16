package kh.cmt.auth.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * What {@code POST /login} answers. The admin web's sign-in screen asks with {@code Accept: application/json}: it gets
 * {@code {next}} (the authorization request to resume; null when none) or 401 {@code {error}} (BAD_CREDENTIALS,
 * LOCKED, DISABLED, ACCESS_EXPIRED) and moves on itself. A plain form post is redirected as before.
 */
public final class LoginResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoginResponses() {
    }

    public static AuthenticationSuccessHandler success(WebLoginPages pages) {
        SavedRequestAwareAuthenticationSuccessHandler plain = new SavedRequestAwareAuthenticationSuccessHandler();
        return (request, response, authentication) -> {
            if (!wantsJson(request)) {
                plain.onAuthenticationSuccess(request, response, authentication);
                return;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("next", pages.takeSavedRequest(request, response));
            write(response, HttpServletResponse.SC_OK, body);
        };
    }

    public static AuthenticationFailureHandler failure(WebLoginPages pages) {
        return (request, response, exception) -> {
            String code = code(exception);
            if (wantsJson(request)) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", code));
            } else if (pages.external()) {
                pages.redirect(request, response, pages.error(code));
            } else {
                new SimpleUrlAuthenticationFailureHandler("/login?error").onAuthenticationFailure(request, response, exception);
            }
        };
    }

    static String code(AuthenticationException e) {
        if (e instanceof LockedException) {
            return "LOCKED";
        }
        if (e instanceof DisabledException) {
            return "DISABLED";
        }
        if (e instanceof AccountExpiredException) {
            return "ACCESS_EXPIRED";
        }
        return "BAD_CREDENTIALS";
    }

    public static boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    public static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), body);
    }
}
