package kh.cmt.auth.twofactor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import kh.cmt.auth.login.LoginAttemptService;
import kh.cmt.auth.login.WebLoginPages;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * The steps of the second login factor shared by this server's pages and the admin web's screens: the authenticator
 * being set up (its secret waits in the session until a first code confirms it), a right code (the session is marked
 * and the login resumes), a wrong one (counted; at the limit the account is locked and the session ended).
 */
@Component
public class SecondFactorSteps {

    static final String PENDING_SECRET = "pendingTotpSecret";

    /** What the enrolment screen shows: the QR code (inline SVG) and the same key for typing in by hand. */
    public record Enrolment(String qrSvg, String secret) {
    }

    private final TwoFactorService twoFactor;
    private final LoginAttemptService attempts;
    private final WebLoginPages pages;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    SecondFactorSteps(TwoFactorService twoFactor, LoginAttemptService attempts, WebLoginPages pages) {
        this.twoFactor = twoFactor;
        this.attempts = attempts;
        this.pages = pages;
    }

    /** The secret being set up in this session (a new one the first time). */
    public String pendingSecret(HttpSession session) {
        String secret = (String) session.getAttribute(PENDING_SECRET);
        if (secret == null) {
            secret = Totp.newSecret();
            session.setAttribute(PENDING_SECRET, secret);
        }
        return secret;
    }

    public Enrolment enrolment(HttpSession session, String username) {
        String secret = pendingSecret(session);
        return new Enrolment(QrSvg.of(Totp.otpAuthUri(twoFactor.issuerName(), username, secret)), secret);
    }

    /** Confirms the authenticator with its first code; false when the code is wrong or nothing is being set up. */
    public boolean confirmEnrolment(HttpSession session, java.util.UUID accountId, String code) {
        String secret = (String) session.getAttribute(PENDING_SECRET);
        if (secret == null || !twoFactor.confirmEnrolment(accountId, secret, code.trim())) {
            return false;
        }
        session.removeAttribute(PENDING_SECRET);
        return true;
    }

    /** Second step done: the session gets FACTOR_OTP (amr otp / sms). Returns the authorization request to resume. */
    public String finish(HttpServletRequest request, HttpServletResponse response, boolean viaSms) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        List<GrantedAuthority> authorities = new ArrayList<>(current.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(TwoFactorService.FACTOR_OTP));
        if (viaSms) {
            authorities.add(new SimpleGrantedAuthority(TwoFactorService.FACTOR_SMS));
        }
        UsernamePasswordAuthenticationToken upgraded =
                UsernamePasswordAuthenticationToken.authenticated(current.getPrincipal(), null, authorities);
        upgraded.setDetails(current.getDetails());
        SecurityContext context = new SecurityContextImpl(upgraded);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        attempts.clearOtp(current.getName());
        return pages.takeSavedRequest(request, response);
    }

    /** A wrong code: counted. True when this one locked the account (the session is then ended). */
    public boolean failed(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (attempts.recordOtpFailure(authentication.getName())) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            return true;
        }
        return false;
    }
}
