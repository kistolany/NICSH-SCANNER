package kh.cmt.auth.twofactor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.login.WebLoginPages;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second step of the login. A user who signed in with a password but has not yet entered a one-time code is
 * sent to the code page (or to the enrolment page the first time) instead of getting an authorization code.
 */
public class SecondFactorFilter extends OncePerRequestFilter {

    private final AccountRepository accounts;
    private final TwoFactorService twoFactor;
    private final WebLoginPages pages;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public SecondFactorFilter(AccountRepository accounts, TwoFactorService twoFactor, WebLoginPages pages) {
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.pages = pages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getRequestURI().equals("/oauth2/authorize")
                && authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !hasSecondFactor(authentication)) {
            var account = accounts.findByUsername(authentication.getName());
            if (account.isPresent() && twoFactor.requiredFor(account.get())) {
                requestCache.saveRequest(request, response);
                pages.redirect(request, response, pages.step(twoFactor.isEnrolled(account.get().id()) ? "otp" : "enroll"));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public static boolean hasSecondFactor(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> TwoFactorService.FACTOR_OTP.equals(a.getAuthority()));
    }
}
