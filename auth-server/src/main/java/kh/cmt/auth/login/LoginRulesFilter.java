package kh.cmt.auth.login;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import kh.cmt.auth.login.LoginRuleService.Refusal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Checks the account and its role before an authorization code is handed out: right after the password, and again
 * whenever a still-open login session asks for a new code. Runs before the password-change and second-step filters,
 * so nobody is asked for a code they could not use.
 * <ul>
 *   <li>the account was made inactive, or "sign out everywhere" happened after this login session started: signed
 *       out and sent to the login page (the authorization request resumes after the new login)</li>
 *   <li>its access has ended, or the role's days / hours / addresses do not allow it: signed out and sent to
 *       {@code /login/denied?reason=...}</li>
 * </ul>
 */
public class LoginRulesFilter extends OncePerRequestFilter {

    private final AccountRepository accounts;
    private final LoginRuleService rules;
    private final WebLoginPages pages;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public LoginRulesFilter(AccountRepository accounts, LoginRuleService rules, WebLoginPages pages) {
        this.accounts = accounts;
        this.rules = rules;
        this.pages = pages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getRequestURI().equals("/oauth2/authorize")
                && authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            Optional<AccountClaims> account = accounts.findByUsername(authentication.getName());
            if (account.isEmpty() || !account.get().isActive() || revokedSince(request.getSession(false), account.get())) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                requestCache.saveRequest(request, response);
                pages.redirect(request, response, pages.login());
                return;
            }
            Optional<Refusal> refusal = rules.check(account.get(), request.getRemoteAddr());
            if (refusal.isPresent()) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                pages.redirect(request, response, pages.denied(refusal.get().name()));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The login session started before "sign out everywhere" (the session id changes at login, its start does not). */
    private static boolean revokedSince(HttpSession session, AccountClaims account) {
        return account.sessionsRevokedAt() != null && session != null
                && Instant.ofEpochMilli(session.getCreationTime()).isBefore(account.sessionsRevokedAt());
    }
}
