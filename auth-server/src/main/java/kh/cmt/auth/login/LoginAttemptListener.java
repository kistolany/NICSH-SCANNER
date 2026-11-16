package kh.cmt.auth.login;

import java.time.Clock;
import kh.cmt.auth.account.AccountRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Feeds password logins into {@link LoginAttemptService} and records the time of the last good one. Client
 * authentication is not counted here.
 */
@Component
class LoginAttemptListener {

    private final LoginAttemptService attempts;
    private final AccountRepository accounts;
    private final Clock clock;

    LoginAttemptListener(LoginAttemptService attempts, AccountRepository accounts, Clock clock) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.clock = clock;
    }

    @EventListener
    void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken) {
            attempts.recordFailure(event.getAuthentication().getName());
        }
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken) {
            attempts.clear(event.getAuthentication().getName());
            accounts.recordLogin(event.getAuthentication().getName(), clock.instant());
        }
    }
}
