package kh.cmt.auth.login;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kh.cmt.auth.account.AccountRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First login with a generated password: before an authorization code is handed out, send the user to the
 * change-password page. The authorization request is saved and resumed after the change.
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private final AccountRepository accounts;
    private final WebLoginPages pages;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public MustChangePasswordFilter(AccountRepository accounts, WebLoginPages pages) {
        this.accounts = accounts;
        this.pages = pages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getRequestURI().equals("/oauth2/authorize")
                && authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && accounts.mustChangePassword(authentication.getName())) {
            requestCache.saveRequest(request, response);
            pages.redirect(request, response, pages.step("new-password"));
            return;
        }
        chain.doFilter(request, response);
    }
}
