package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kh.cmt.auth.support.AuthServerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Step 1.2 "done when": the decoded JWT shows the claims; an old refresh token used twice kills the session. */
@AuthServerTest
class TokenClaimsTest {

    static final String SCHOOL = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    OAuthFlow flow;

    @BeforeEach
    void setUp() {
        flow = new OAuthFlow(mvc, json);
    }

    @AfterEach
    void resetAccount() {
        jdbc.sql("""
                update account set person_id = null, device_id = null, role = 'staff', perm_ver = 1,
                                   locale = 'km', status = 'active'
                where username = 'test.staff'
                """).update();
    }

    @Test
    void appTokenCarriesTheClaims() throws Exception {
        UUID person = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        jdbc.sql("update account set person_id = :p, device_id = :d, role = 'teacher', perm_ver = 7, locale = 'en' "
                + "where username = 'test.staff'").param("p", person).param("d", device).update();
        UUID accountId = jdbc.sql("select id from account where username = 'test.staff'").query(UUID.class).single();

        JsonNode tokens = flow.login("test.staff", "Test@12345");
        JWTClaimsSet claims = SignedJWT.parse(tokens.get("access_token").asText()).getJWTClaimsSet();

        assertThat(claims.getSubject()).isEqualTo(accountId.toString());
        assertThat(claims.getStringClaim("typ")).isEqualTo("app");
        assertThat(claims.getStringClaim("person_id")).isEqualTo(person.toString());
        assertThat(claims.getStringClaim("role")).isEqualTo("teacher");
        assertThat(claims.getLongClaim("perm_ver")).isEqualTo(7);
        assertThat(claims.getStringClaim("device_id")).isEqualTo(device.toString());
        assertThat(claims.getStringListClaim("amr")).containsExactly("pwd");
        assertThat(claims.getStringClaim("locale")).isEqualTo("en");
        assertThat(claims.getStringClaim("school_id")).isEqualTo(SCHOOL);
        assertThat(claims.getClaim("web_user_id")).isNull();
        // 15-minute access token
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(15));
        // the id token carries the same identity
        JWTClaimsSet idClaims = SignedJWT.parse(tokens.get("id_token").asText()).getJWTClaimsSet();
        assertThat(idClaims.getSubject()).isEqualTo(accountId.toString());
        assertThat(idClaims.getStringClaim("typ")).isEqualTo("app");
    }

    @Test
    void webTokenHasWebTypeAndNoDevice() throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(flow.login("test.admin", "Test@12345").get("access_token").asText())
                .getJWTClaimsSet();
        assertThat(claims.getStringClaim("typ")).isEqualTo("web");
        assertThat(claims.getStringClaim("role")).isEqualTo("super_admin");
        assertThat(claims.getClaim("device_id")).isNull();
    }

    @Test
    void refreshPicksUpAChangedRoleAndPermissionVersion() throws Exception {
        JsonNode first = flow.login("test.staff", "Test@12345");
        jdbc.sql("update account set role = 'admin', perm_ver = 2 where username = 'test.staff'").update();

        JsonNode refreshed = flow.body(flow.refresh(first.get("refresh_token").asText()));
        JWTClaimsSet claims = SignedJWT.parse(refreshed.get("access_token").asText()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("role")).isEqualTo("admin");
        assertThat(claims.getLongClaim("perm_ver")).isEqualTo(2);
    }

    @Test
    void refreshTokenIs30DaysAndEachOneIsRecordedAsAHash() throws Exception {
        JsonNode first = flow.login("test.staff", "Test@12345");
        String refresh1 = first.get("refresh_token").asText();
        String refresh2 = flow.body(flow.refresh(refresh1)).get("refresh_token").asText();

        var rows = jdbc.sql("""
                select token_hash, rotated_from is not null as rotated, used_at is not null as used,
                       extract(epoch from (expires_at - issued_at)) / 86400 as days
                from refresh_token where family_id = (
                    select family_id from refresh_token where token_hash = :h1)
                order by issued_at
                """).param("h1", RefreshTokenHash.of(refresh1)).query().listOfRows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("used")).isEqualTo(true);       // first one was replaced
        assertThat(rows.get(1).get("rotated")).isEqualTo(true);    // second points back at the first
        assertThat(rows.get(1).get("token_hash")).isEqualTo(RefreshTokenHash.of(refresh2));
        assertThat(((Number) rows.get(0).get("days")).doubleValue()).isBetween(29.99, 30.01);
        assertThat(rows.get(0).get("token_hash").toString()).hasSize(64).doesNotContain(refresh1);
    }

    @Test
    void usingAnOldRefreshTokenTwiceRevokesTheWholeSession() throws Exception {
        String refresh1 = flow.login("test.staff", "Test@12345").get("refresh_token").asText();

        MvcResult ok = flow.refresh(refresh1);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String refresh2 = flow.body(ok).get("refresh_token").asText();

        // the thief (or the app, by mistake) presents the OLD token again
        MvcResult replay = flow.refresh(refresh1);
        assertThat(replay.getResponse().getStatus()).isEqualTo(400);
        assertThat(replay.getResponse().getContentAsString()).contains("invalid_grant");

        // ...and now even the newest token is dead: the whole family was revoked
        MvcResult newest = flow.refresh(refresh2);
        assertThat(newest.getResponse().getStatus()).isEqualTo(400);

        Integer stillActive = jdbc.sql("""
                select count(*) from refresh_token
                where family_id = (select family_id from refresh_token where token_hash = :h)
                  and revoked_at is null
                """).param("h", RefreshTokenHash.of(refresh1)).query(Integer.class).single();
        assertThat(stillActive).isZero();
    }

    @Test
    void anotherSessionOfTheSameUserIsNotAffected() throws Exception {
        String sessionA = flow.login("test.staff", "Test@12345").get("refresh_token").asText();
        String sessionB = flow.login("test.staff", "Test@12345").get("refresh_token").asText();

        flow.refresh(sessionA);
        flow.refresh(sessionA);   // reuse kills session A only

        assertThat(flow.refresh(sessionB).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void inactiveAccountCannotRefresh() throws Exception {
        String refresh = flow.login("test.staff", "Test@12345").get("refresh_token").asText();
        jdbc.sql("update account set status = 'inactive' where username = 'test.staff'").update();

        MvcResult result = flow.refresh(refresh);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(List.of("invalid_grant")).allMatch(result.getResponse().getContentAsString()::contains);
    }
}
