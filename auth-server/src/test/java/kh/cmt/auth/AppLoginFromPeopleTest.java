package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.OffsetDateTime;
import java.util.UUID;
import kh.cmt.auth.OAuthFlow.Attempt;
import kh.cmt.auth.support.AuthServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Step 4.2 "done when": a new staff member logs in on the app with the generated password and must change it.
 * <p>
 * The account is written exactly as cmt-api's iam module writes it when a person is created with a login
 * ({@code AuthAccountStore.createApp}): type app, linked person, role, a {@code {bcrypt}} hash of the generated
 * password, must change password.
 */
@AuthServerTest
class AppLoginFromPeopleTest {

    static final UUID SCHOOL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String GENERATED = "Kx7#mPq2Wd";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void aNewStaffMemberLogsInWithTheGeneratedPasswordAndMustChangeIt() throws Exception {
        UUID person = UUID.randomUUID();
        String username = "sokha.lim" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.sql("""
                insert into account (id, school_id, username, password_hash, type, person_id, status, role, phone,
                                     must_change_password, created_at, updated_at)
                values (:id, :s, :u, :hash, 'app', :p, 'active', 'teacher', '012 345 678', true, :now, :now)
                """)
                .param("id", UUID.randomUUID()).param("s", SCHOOL).param("u", username)
                .param("hash", "{bcrypt}" + new BCryptPasswordEncoder().encode(GENERATED)).param("p", person)
                .param("now", OffsetDateTime.now()).update();

        OAuthFlow flow = new OAuthFlow(mvc, json);
        Attempt attempt = flow.begin();
        flow.submitLogin(attempt, username, GENERATED);
        assertThat(flow.authorize(attempt).getResponse().getRedirectedUrl()).endsWith("/password/change");   // must change

        mvc.perform(post("/password/change").session(attempt.session()).with(csrf())
                .param("currentPassword", GENERATED).param("newPassword", "Mine#Own2026").param("confirmPassword", "Mine#Own2026"));
        JWTClaimsSet claims = SignedJWT.parse(flow.exchange(attempt, flow.authorize(attempt).getResponse().getRedirectedUrl())
                .get("access_token").asText()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("typ")).isEqualTo("app");
        assertThat(claims.getStringClaim("role")).isEqualTo("teacher");
        assertThat(claims.getStringClaim("person_id")).isEqualTo(person.toString());

        // the new password is stored the auth server's way, and the generated one no longer works
        assertThat(jdbc.sql("select password_hash from account where username = :u").param("u", username).query(String.class).single())
                .startsWith("{argon2}");
        assertThat(flow.submitLogin(flow.begin(), username, GENERATED).getResponse().getRedirectedUrl()).contains("/login?error");
        assertThat(flow.login(username, "Mine#Own2026").hasNonNull("access_token")).isTrue();
    }
}
