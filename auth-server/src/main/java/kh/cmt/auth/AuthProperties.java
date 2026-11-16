package kh.cmt.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code auth.*} in application.yml. */
@ConfigurationProperties("auth")
public record AuthProperties(String issuer, Keys keys, Tokens tokens, Clients clients, Lockout lockout,
        TwoFactor twoFactor, RateLimit rateLimit, Web web, Dev dev) {

    /**
     * The admin web's own sign-in screens. {@code loginUrl} (e.g. https://admin.cmt.school/login): the browser is sent
     * there instead of this server's pages, and the screens talk to {@code /login} and {@code /login/api/**} from
     * {@code allowedOrigins} (CORS, with the session cookie). Blank: this server's own plain pages.
     */
    public record Web(String loginUrl, List<String> allowedOrigins) {
    }

    public record Keys(String encryptionSecret) {
    }

    public record Tokens(Duration accessTtl, Duration mobileRefreshTtl, Duration webRefreshTtl) {
    }

    public record Clients(Mobile mobile, WebBff webBff, Jobs jobs) {
    }

    public record Mobile(List<String> redirectUris) {
    }

    public record WebBff(String secret, List<String> redirectUris, List<String> postLogoutRedirectUris) {
    }

    public record Jobs(String secret) {
    }

    /** {@code maxAttempts} wrong passwords within {@code window} lock the account for {@code lockDuration}. */
    public record Lockout(int maxAttempts, Duration lockDuration, Duration window) {
    }

    /** {@code issuerName} is the label shown in the authenticator app; the SMS code lives {@code smsCodeTtl}. */
    public record TwoFactor(String issuerName, Duration smsCodeTtl, Duration smsCooldown) {
    }

    /** Requests per IP for the login-type endpoints. */
    public record RateLimit(boolean enabled, Limit login) {
    }

    public record Limit(long capacity, Duration window) {
    }

    /** Dev-only helpers (profile "dev"); null/false elsewhere. */
    public record Dev(boolean seedTestAccounts, String testPassword) {
    }
}
