package kh.cmt.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Step 4.2, cmt-api side: creating a person creates the app login (username from the name, generated first password,
 * must change it). The auth server side — the new staff member logs in with that password and must change it — is
 * {@code AppLoginFromPeopleTest} in the auth server.
 */
@ApiTest
class AppLoginTest {

    private static final String PEOPLE = "/api/v1/admin/people";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private UUID school;
    private Caller admin;
    private String surname;

    @BeforeEach
    void schoolOfItsOwn() {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Login school', now(), now())")
                .param("id", school).param("c", "L" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        surname = "Lim" + UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9]", "x");   // usernames are global
    }

    // ------------------------------------------------------------------ the "done when" check (cmt-api part)

    @Test
    void addPersonWithLoginCreatesTheAccountWithAGeneratedPasswordThatMustBeChanged() throws Exception {
        JsonNode created = body(create(person("Sokha " + surname, "teacher"), Map.of()).andExpect(status().isCreated()));
        String username = ("sokha." + surname).toLowerCase();
        JsonNode login = created.get("newLogin");
        assertThat(login.get("username").asText()).isEqualTo(username);
        assertThat(login.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(login.get("sentBy").asText()).isEqualTo("none");
        String password = login.get("temporaryPassword").asText();
        assertThat(password).hasSizeGreaterThanOrEqualTo(10).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");

        Map<String, Object> account = account(created);
        assertThat(account).containsEntry("username", username).containsEntry("type", "app").containsEntry("role", "teacher")
                .containsEntry("status", "active").containsEntry("must_change_password", true)
                .containsEntry("school_id", school);
        String hash = (String) account.get("password_hash");
        assertThat(hash).startsWith("{bcrypt}");
        assertThat(new BCryptPasswordEncoder().matches(password, hash.substring("{bcrypt}".length()))).isTrue();

        // the person page shows the login, never the password again
        JsonNode read = body(mvc.perform(get(PEOPLE + "/" + created.get("id").asText()).header("Authorization", admin.bearer())));
        assertThat(read.get("login").get("username").asText()).isEqualTo(username);
        assertThat(read.get("login").get("mustChangePassword").asBoolean()).isTrue();
        assertThat(read.has("newLogin")).isFalse();
        assertThat(read.toString()).doesNotContain(password);
    }

    @Test
    void usernamesAreMadeFromTheNameAndUsedOnce() throws Exception {
        JsonNode first = body(create(person("Dara " + surname, "staff"), Map.of()));
        JsonNode second = body(create(person("Dara " + surname, "staff"), Map.of()));
        String base = ("dara." + surname).toLowerCase();
        assertThat(first.get("newLogin").get("username").asText()).isEqualTo(base);
        assertThat(second.get("newLogin").get("username").asText()).isEqualTo(base + "2");

        // no Latin letters in the name: from the Staff ID
        JsonNode khmer = body(create(person("តារា", "staff"), Map.of()).andExpect(status().isCreated()));
        assertThat(khmer.get("newLogin").get("username").asText()).isEqualTo(khmer.get("staffId").asText().toLowerCase());

        // typed: used once (web users too), and a sensible shape
        create(person("Other Person", "staff"), Map.of("username", base.toUpperCase())).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        create(person("Other Person", "staff"), Map.of("username", "a b")).andExpect(status().isBadRequest());
        // a failed login creates no person either (one transaction)
        assertThat(jdbc.sql("select count(*) from person where school_id = :s and name_en = 'Other Person'").param("s", school)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aTypedPasswordFollowsTheSchoolsRules() throws Exception {
        create(person("Weak " + surname, "staff"), Map.of("password", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"))
                .andExpect(jsonPath("$.violations[0]").value("MIN_LENGTH"));
        JsonNode created = body(create(person("Strong " + surname, "staff"),
                Map.of("password", "Cmt#48210x", "mustChangePassword", false)).andExpect(status().isCreated()));
        assertThat(created.get("newLogin").has("temporaryPassword")).isFalse();   // typed by the admin: not echoed
        assertThat(account(created)).containsEntry("must_change_password", false);
    }

    @Test
    void theAccountFollowsThePersonsRoleAndPhone() throws Exception {
        JsonNode created = body(create(person("Kosal " + surname, "staff"), Map.of()));
        ObjectNode form = (ObjectNode) json.readTree(json.writeValueAsString(person("Kosal " + surname, "teacher")));
        form.put("phone", "012 777 888").put("version", created.get("version").asLong());
        mvc.perform(put(PEOPLE + "/" + created.get("id").asText()).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(form.toString())).andExpect(status().isOk());
        assertThat(account(created)).containsEntry("role", "teacher").containsEntry("phone", "012 777 888");
    }

    // ------------------------------------------------------------------ send login, reset password

    @Test
    void sendLoginCreatesTheLoginOrGivesANewTemporaryPassword() throws Exception {
        JsonNode noLogin = body(mvc.perform(post(PEOPLE).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(person("Navy " + surname, "staff")))).andExpect(status().isCreated()));
        assertThat(noLogin.get("hasLogin").asBoolean()).isFalse();
        String id = noLogin.get("id").asText();

        String username = ("navy.c" + surname).toLowerCase();
        JsonNode sent = body(sendLogin(id, "{\"username\":\"" + username + "\"}").andExpect(status().isOk()));
        assertThat(sent.get("username").asText()).isEqualTo(username);
        String hashBefore = (String) account(noLogin).get("password_hash");

        JsonNode again = body(sendLogin(id, "{\"send\":\"none\"}").andExpect(status().isOk()));
        assertThat(again.get("username").asText()).isEqualTo(username);
        assertThat(again.get("temporaryPassword").asText()).isNotEqualTo(sent.get("temporaryPassword").asText());
        assertThat(account(noLogin).get("password_hash")).isNotEqualTo(hashBefore);

        // SMS needs a phone, email an address
        sendLogin(id, "{\"send\":\"sms\"}").andExpect(jsonPath("$.errors[0].rule").value("NoPhone"));
        sendLogin(id, "{\"send\":\"email\"}").andExpect(jsonPath("$.errors[0].rule").value("NoEmail"));
        sendLogin(id, "{\"send\":\"pigeon\"}").andExpect(jsonPath("$.errors[0].rule").value("OneOf"));
    }

    @Test
    void resetPasswordGivesATemporaryPasswordAndSignsTheUserOut() throws Exception {
        Map<String, Object> form = new HashMap<>(person("Ratana " + surname, "staff"));
        form.put("phone", "012 333 444");
        JsonNode created = body(create(form, Map.of("password", "Known#Pass9", "mustChangePassword", false)));
        Map<String, Object> account = account(created);
        UUID accountId = (UUID) account.get("id");
        jdbc.sql("update auth.account set locked_until = now() + interval '10 minutes', failed_attempts = 5 where id = :a")
                .param("a", accountId).update();
        Caller app = TestJwt.withPerson("app", "staff", school, accountId, UUID.fromString(created.get("id").asText()));
        mvc.perform(get("/api/v1/app/me/profile").header("Authorization", app.bearer())).andExpect(status().isOk());
        Thread.sleep(1100);

        JsonNode reset = body(mvc.perform(post(PEOPLE + "/" + created.get("id").asText() + "/reset-password")
                .header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(reset.get("sentBy").asText()).isEqualTo("sms");   // by SMS unless said otherwise
        assertThat(reset.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(account(created)).containsEntry("must_change_password", true).containsEntry("failed_attempts", 0)
                .containsEntry("locked_until", null);
        mvc.perform(get("/api/v1/app/me/profile").header("Authorization", app.bearer())).andExpect(status().isUnauthorized());

        // nothing to reset without a login, and nothing for inactive people
        JsonNode noLogin = body(mvc.perform(post(PEOPLE).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(person("Without " + surname, "staff")))));
        mvc.perform(post(PEOPLE + "/" + noLogin.get("id").asText() + "/reset-password").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"send\":\"none\"}")).andExpect(jsonPath("$.code").value("NO_LOGIN"));
        mvc.perform(post(PEOPLE + "/" + created.get("id").asText() + "/inactivate").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"RESIGNED\"}")).andExpect(status().isNoContent());
        sendLogin(created.get("id").asText(), "{}").andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> person(String nameEn, String role) {
        return Map.of("name", Map.of("km", "ឈ្មោះ", "en", nameEn), "role", role);
    }

    private ResultActions create(Map<String, Object> person, Map<String, Object> login) throws Exception {
        Map<String, Object> form = new HashMap<>(person);
        form.put("login", login);
        return mvc.perform(post(PEOPLE).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form)));
    }

    private ResultActions sendLogin(String id, String body) throws Exception {
        return mvc.perform(post(PEOPLE + "/" + id + "/send-login").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> account(JsonNode person) {
        return jdbc.sql("select * from auth.account where person_id = :p and type = 'app'")
                .param("p", UUID.fromString(person.get("id").asText())).query().singleRow();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
