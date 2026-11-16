package kh.cmt.auth.token;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kh.cmt.auth.account.AccountRepository;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import kh.cmt.auth.login.LoginRuleService;
import kh.cmt.auth.twofactor.TwoFactorService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Puts the CMT claims into the access token and the id token:
 * <pre>
 * sub        account id (uuid)             typ        app | web (job for client-credentials tokens)
 * school_id  tenant                        role       role code
 * person_id  app users (when linked)       web_user_id web users (when linked)
 * perm_ver   permission version            device_id  bound phone (app only, when bound)
 * amr        how the user signed in        locale     km | en | ar
 * </pre>
 * Web users whose role has login rules also get, for the BFF: {@code sess_min} (idle timeout), {@code login_from} /
 * {@code login_to} ("HH:mm"), {@code login_days} (ISO weekdays), {@code login_ips}, {@code login_tz} (zone of the
 * hours). A token is refused outside the allowed days and hours (also on refresh).
 * Read from the account at every issuance, so a refresh picks up a new role / perm_ver / locale, and an
 * account that was made inactive can no longer get new tokens. The API loads the actual permissions by
 * {@code role} + {@code perm_ver}; they are never put in the token.
 */
@Component
class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final AccountRepository accounts;
    private final TwoFactorService twoFactor;
    private final LoginRuleService loginRules;
    private final Clock clock;

    TokenClaimsCustomizer(AccountRepository accounts, TwoFactorService twoFactor, LoginRuleService loginRules, Clock clock) {
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.loginRules = loginRules;
        this.clock = clock;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
        boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
        if (!accessToken && !idToken) {
            return;
        }
        JwtClaimsSet.Builder claims = context.getClaims();

        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            claims.claim("typ", "job");
            return;
        }

        AccountClaims account = accounts.findByUsername(context.getPrincipal().getName())
                .filter(AccountClaims::isActive)
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT)));

        // A session that never did the second step (e.g. the role started requiring it after login) must log in again.
        if (twoFactor.requiredFor(account) && !has(context.getPrincipal(), TwoFactorService.FACTOR_OTP)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
        }
        // After the last day of access, or outside the role's days / hours, no new tokens, not even by refresh. (The
        // caller here is the BFF, not the browser, so the address is checked at /oauth2/authorize and by the BFF.)
        Optional<LoginRuleService.Rules> rules = loginRules.rulesFor(account);
        Optional<LoginRuleService.Refusal> refusal = loginRules.check(account, null);
        if (refusal.isPresent()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    "LOGIN_NOT_ALLOWED: " + refusal.get().name(), null));
        }

        claims.subject(account.id().toString());
        claims.claim("typ", account.type());
        claims.claim("school_id", account.schoolId().toString());
        claims.claim("role", account.role());
        claims.claim("perm_ver", account.permVer());
        claims.claim("locale", account.locale());
        claims.claim("amr", authenticationMethods(context.getPrincipal()));
        if (account.personId() != null) {
            claims.claim("person_id", account.personId().toString());
        }
        if (account.webUserId() != null) {
            claims.claim("web_user_id", account.webUserId().toString());
        }
        if (account.deviceId() != null && "app".equals(account.type())) {
            claims.claim("device_id", account.deviceId().toString());
        }
        rules.ifPresent(r -> loginRuleClaims(claims, r));
    }

    /** What the BFF checks on every request (and the idle timeout it gives the session). Only what is set. */
    private void loginRuleClaims(JwtClaimsSet.Builder claims, LoginRuleService.Rules rules) {
        if (rules.sessionTimeoutMinutes() != null) {
            claims.claim("sess_min", rules.sessionTimeoutMinutes());
        }
        boolean restricted = false;
        if (rules.from() != null && rules.to() != null) {
            claims.claim("login_from", HH_MM.format(rules.from()));
            claims.claim("login_to", HH_MM.format(rules.to()));
            restricted = true;
        }
        if (rules.days() != null && !rules.days().isEmpty() && rules.days().size() < 7) {
            claims.claim("login_days", new ArrayList<>(rules.days()));   // ArrayList: see authenticationMethods
            restricted = true;
        }
        if (rules.ips() != null && !rules.ips().isEmpty()) {
            claims.claim("login_ips", new ArrayList<>(rules.ips()));
            restricted = true;
        }
        if (restricted) {
            claims.claim("login_tz", clock.getZone().getId());   // the zone the hours and days are meant in
        }
    }

    /** "pwd" for a password login, plus "otp" (and "sms") once the second step is done. */
    private static List<String> authenticationMethods(Authentication principal) {
        List<String> methods = new ArrayList<>();   // ArrayList: Spring's JSON allowlist rejects immutable lists
        if (principal instanceof UsernamePasswordAuthenticationToken) {
            methods.add("pwd");
        }
        if (has(principal, TwoFactorService.FACTOR_OTP)) {
            methods.add("otp");
        }
        if (has(principal, TwoFactorService.FACTOR_SMS)) {
            methods.add("sms");
        }
        return methods;
    }

    private static boolean has(Authentication principal, String authority) {
        return principal.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
