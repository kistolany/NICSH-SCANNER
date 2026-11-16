package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.AuthServerTest;
import kh.cmt.auth.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Step 3.5: the role's login rules (allowed hours, days and addresses) at sign-in and token refresh, and the rule
 * claims the BFF checks on every request.
 */
@AuthServerTest
class LoginRulesTest {

    static final String PASSWORD = "Office#Pass1";
    static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestAccounts accounts;
    @Autowired Clock clock;

    OAuthFlow flow;
    String role;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
        role = "role_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void outsideTheAllowedHoursTheLoginIsRefusedAfterThePassword() throws Exception {
        accounts.setLoginRules(role, 480, later(1), later(2), null, null);
        String user = accounts.createWeb("hours", PASSWORD, role, null);

        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        MvcResult refused = flow.authorize(attempt);
        assertThat(refused.getResponse().getRedirectedUrl()).isEqualTo("/login/denied?reason=OUTSIDE_HOURS");

        // signed out again: no authorization code, the next try asks for the password
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/login");
        assertThat(page("/login/denied?reason=OUTSIDE_HOURS")).contains("data-code=\"OUTSIDE_HOURS\"");
    }

    @Test
    void onADayThatIsNotAllowedTheLoginIsRefused() throws Exception {
        int today = java.time.LocalDate.now(clock).getDayOfWeek().getValue();
        accounts.setLoginRules(role, 480, null, null, IntStream.rangeClosed(1, 7).filter(d -> d != today).boxed().toList(), null);
        String user = accounts.createWeb("days", PASSWORD, role, null);

        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).isEqualTo("/login/denied?reason=DAY_NOT_ALLOWED");
    }

    @Test
    void fromAnAddressOutsideTheAllowedRangesTheLoginIsRefused() throws Exception {
        accounts.setLoginRules(role, 480, null, null, null, List.of("10.20.0.0/16", "192.168.7.7"));
        String user = accounts.createWeb("ip", PASSWORD, role, null);   // MockMvc requests come from 127.0.0.1

        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, user, PASSWORD);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).isEqualTo("/login/denied?reason=IP_NOT_ALLOWED");
    }

    @Test
    void withinTheRulesTheTokenCarriesThemForTheBff() throws Exception {
        String from = earlier(1);
        String to = later(1);
        int today = java.time.LocalDate.now(clock).getDayOfWeek().getValue();
        accounts.setLoginRules(role, 45, from, to, List.of(today), List.of("127.0.0.0/8", "::1"));
        String user = accounts.createWeb("ok", PASSWORD, role, null);

        JWTClaimsSet claims = claims(flow.login(user, PASSWORD));
        assertThat(claims.getIntegerClaim("sess_min")).isEqualTo(45);
        assertThat(claims.getStringClaim("login_from")).isEqualTo(from);
        assertThat(claims.getStringClaim("login_to")).isEqualTo(to);
        assertThat(claims.getListClaim("login_days")).hasSize(1);
        assertThat(claims.getStringListClaim("login_ips")).containsExactly("127.0.0.0/8", "::1");
        assertThat(claims.getStringClaim("login_tz")).isEqualTo(clock.getZone().getId());
    }

    @Test
    void aRoleWithoutRestrictionsOnlyGetsItsSessionTimeout() throws Exception {
        accounts.setLoginRules(role, 480, null, null, List.of(1, 2, 3, 4, 5, 6, 7), List.of());
        JWTClaimsSet claims = claims(flow.login(accounts.createWeb("open", PASSWORD, role, null), PASSWORD));
        assertThat(claims.getIntegerClaim("sess_min")).isEqualTo(480);
        assertThat(claims.getClaims()).doesNotContainKeys("login_from", "login_to", "login_days", "login_ips", "login_tz");
    }

    @Test
    void afterTheAllowedHoursATokenRefreshIsRefused() throws Exception {
        String user = accounts.createWeb("refresh", PASSWORD, role, null);
        String refresh = flow.login(user, PASSWORD).get("refresh_token").asText();   // no rules yet

        accounts.setLoginRules(role, 480, later(1), later(2), null, null);
        MvcResult refused = flow.refresh(refresh);
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = flow.body(refused);
        assertThat(error.get("error").asText()).isEqualTo("invalid_grant");
        assertThat(error.get("error_description").asText()).contains("OUTSIDE_HOURS");
    }

    @Test
    void appAccountsAreNotTouchedByTheRules() throws Exception {
        accounts.setLoginRules("staff", 15, later(1), later(2), null, List.of("10.99.0.0/16"));
        try {
            JWTClaimsSet claims = claims(flow.login("test.staff", "Test@12345"));
            assertThat(claims.getStringClaim("typ")).isEqualTo("app");
            assertThat(claims.getClaims()).doesNotContainKeys("sess_min", "login_from");
        } finally {
            accounts.clearLoginRules("staff");
        }
    }

    @Test
    void theDeniedPageOnlyShowsKnownCodes() throws Exception {
        assertThat(page("/login/denied?reason=<script>")).contains("data-code=\"LOGIN_NOT_ALLOWED\"").doesNotContain("<script>");
    }

    // ------------------------------------------------------------------ helpers

    /** "HH:mm" so many hours from now, in school time. */
    private String later(int hours) {
        return HH_MM.format(LocalTime.now(clock).plusHours(hours));
    }

    private String earlier(int hours) {
        return HH_MM.format(LocalTime.now(clock).minusHours(hours));
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }

    private static JWTClaimsSet claims(JsonNode tokens) throws Exception {
        return SignedJWT.parse(tokens.get("access_token").asText()).getJWTClaimsSet();
    }
}
