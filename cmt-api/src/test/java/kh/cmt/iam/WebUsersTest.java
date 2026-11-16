package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.support.TestRoles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Step 3.6, web users: the list and form, the login account on the auth server, deactivation (signed out at once),
 * access that ends on a date, the "own account" and "last Super Admin" rules, moving users between roles.
 */
@ApiTest
class WebUsersTest {

    private static final String USERS = "/api/v1/admin/web-users";
    private static final String ROLES = "/api/v1/admin/roles";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private final Caller superAdmin = TestJwt.webUser("super_admin");

    // ------------------------------------------------------------------ create and the account

    @Test
    void aNewWebUserGetsALoginAccountAndALinkToSetThePassword() throws Exception {
        String username = unique("dara") + "@cmt.school";
        JsonNode user = body(create(superAdmin, Map.of("name", "Dara  Chan", "username", username, "phone", "+855 12 345 678"))
                .andExpect(status().isCreated()));

        assertThat(user.get("name").asText()).isEqualTo("Dara Chan");
        assertThat(user.get("role").get("code").asText()).isEqualTo("viewer");   // the default role for new web users
        assertThat(user.get("role").get("name").get("en").asText()).isEqualTo("Viewer");
        assertThat(user.get("scope").get("type").asText()).isEqualTo("ALL");      // the role decides, read-only here
        assertThat(user.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(user.get("twoFactor").get("required").asBoolean()).isFalse();
        assertThat(user.has("lastLoginAt")).isFalse();

        Map<String, Object> account = account(user);
        assertThat(account).containsEntry("username", username).containsEntry("type", "web").containsEntry("role", "viewer")
                .containsEntry("status", "active").containsEntry("web_user_id", UUID.fromString(user.get("id").asText()))
                .containsEntry("phone", "+855 12 345 678");
        assertThat((String) account.get("password_hash")).startsWith("{bcrypt}");   // random: nobody knows it
        // the link to set the password: one unused token, valid for 3 days
        assertThat(jdbc.sql("""
                        select count(*) from auth.password_reset_token
                        where account_id = :a and used_at is null and expires_at > now() + interval '71 hours'""")
                .param("a", account.get("id")).query(Integer.class).single()).isEqualTo(1);

        // signed in, the Admin Web header shows their own name, without needing Users › View
        Caller signedIn = TestJwt.ofWebUser("viewer", (UUID) account.get("id"), UUID.fromString(user.get("id").asText()));
        JsonNode me = body(mvc.perform(get("/api/v1/admin/me").header("Authorization", signedIn.bearer())));
        assertThat(me.get("name").asText()).isEqualTo("Dara Chan");
        assertThat(me.get("username").asText()).isEqualTo(username);
        assertThat(me.get("permissions").has("users")).isFalse();
    }

    @Test
    void wrongFormsAreRefused() throws Exception {
        expectErrors(create(superAdmin, Map.of()), "name:NotBlank", "username:NotBlank");
        expectErrors(create(superAdmin, Map.of("name", "X", "username", "a b")), "username:Pattern");
        expectErrors(create(superAdmin, Map.of("name", "X", "username", unique("u"), "role", "no_such_role")), "role:UnknownRole");
        expectErrors(create(superAdmin, Map.of("name", "X", "username", unique("u"), "phone", "call me")), "phone:Pattern");
        expectErrors(create(superAdmin, Map.of("name", "X", "username", unique("u"),
                "accessExpiresOn", LocalDate.now(clock).minusDays(1).toString())), "accessExpiresOn:Past");

        // a username is used by one login only, whatever the case (app accounts included)
        String taken = unique("taken");
        create(superAdmin, Map.of("name", "First", "username", taken)).andExpect(status().isCreated());
        create(superAdmin, Map.of("name", "Second", "username", taken.toUpperCase()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

        // a person is linked to one active web user, and must be an active person of the school
        UUID person = newPerson();
        create(superAdmin, Map.of("name", "Linked", "username", unique("l1"), "linkedPersonId", person.toString()))
                .andExpect(status().isCreated());
        create(superAdmin, Map.of("name", "Linked again", "username", unique("l2"), "linkedPersonId", person.toString()))
                .andExpect(jsonPath("$.code").value("PERSON_TAKEN"));
        expectErrors(create(superAdmin, Map.of("name", "X", "username", unique("u"), "linkedPersonId", UUID.randomUUID().toString())),
                "linkedPersonId:UnknownPerson");

        // someone with a staff-app login can also be a web user: one app and one web account for the same person
        UUID withApp = newPerson();
        mvc.perform(post("/api/v1/admin/people/" + withApp + "/send-login").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"send\":\"none\"}")).andExpect(status().isOk());
        create(superAdmin, Map.of("name", "Office too", "username", unique("l3"), "linkedPersonId", withApp.toString()))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("select count(*) from auth.account where person_id = :p").param("p", withApp)
                .query(Integer.class).single()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ changing

    @Test
    void savingTheFormUpdatesTheAccountToo() throws Exception {
        JsonNode user = newUser(superAdmin, "hr");
        String lastDay = LocalDate.now(clock).plusDays(10).toString();
        UUID person = newPerson();
        JsonNode saved = body(update(superAdmin, user, Map.of("role", "qr_manager", "twoFactorRequired", true,
                "accessExpiresOn", lastDay, "accessExpiresReason", "External auditor", "linkedPersonId", person.toString()))
                .andExpect(status().isOk()));
        assertThat(saved.get("role").get("code").asText()).isEqualTo("qr_manager");
        assertThat(saved.get("expiresInDays").asInt()).isEqualTo(10);
        assertThat(saved.get("accessExpiresReason").asText()).isEqualTo("External auditor");
        assertThat(saved.get("twoFactor").get("required").asBoolean()).isTrue();
        assertThat(account(saved)).containsEntry("role", "qr_manager").containsEntry("two_factor_required", true)
                .containsEntry("access_expires_on", java.sql.Date.valueOf(lastDay)).containsEntry("person_id", person);

        // the version that was read must be sent back
        update(superAdmin, user, Map.of("name", "Late")).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void afterTheLastDayTheUserIsShownAsExpired() throws Exception {
        JsonNode user = newUser(superAdmin, "viewer");
        jdbc.sql("update web_user set access_expires_on = :d where id = :id")
                .param("d", LocalDate.now(clock).minusDays(1)).param("id", UUID.fromString(user.get("id").asText())).update();
        JsonNode now = body(mvc.perform(get(USERS + "/" + user.get("id").asText()).header("Authorization", superAdmin.bearer())));
        assertThat(now.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(now.has("expiresInDays")).isFalse();
        // an unchanged past date may be saved again (only a new date in the past is refused)
        update(superAdmin, now, Map.of("name", "Still expired", "accessExpiresOn", now.get("accessExpiresOn").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void deactivatingSignsTheUserOutEverywhereAtOnce() throws Exception {
        JsonNode user = newUser(superAdmin, "hr");
        UUID accountId = (UUID) account(user).get("id");
        Caller theUser = TestJwt.webUser("hr", TestJwt.SCHOOL, accountId);   // a token issued before the deactivation
        UUID family = UUID.randomUUID();
        jdbc.sql("""
                        insert into auth.refresh_token (id, account_id, family_id, token_hash, issued_at, expires_at)
                        values (:id, :a, :f, :h, now(), now() + interval '8 hours')""")
                .param("id", UUID.randomUUID()).param("a", accountId).param("f", family).param("h", unique("hash")).update();
        jdbc.sql("insert into auth.oauth2_authorization values (:id, 'web-bff', :u, 'authorization_code')")
                .param("id", family.toString()).param("u", user.get("username").asText()).update();
        mvc.perform(get("/api/v1/admin/me").header("Authorization", theUser.bearer())).andExpect(status().isOk());
        Thread.sleep(1100);   // sign-outs are compared by the second the token was issued in

        mvc.perform(delete(USERS + "/" + user.get("id").asText()).param("reason", "Left the school")
                .header("Authorization", superAdmin.bearer())).andExpect(status().isNoContent());

        // the token issued before is refused right away, the refresh token and the stored session are gone
        mvc.perform(get("/api/v1/admin/me").header("Authorization", theUser.bearer())).andExpect(status().isUnauthorized());
        assertThat(account(user)).containsEntry("status", "inactive");
        assertThat(account(user).get("sessions_revoked_at")).isNotNull();
        assertThat(jdbc.sql("select count(*) from auth.refresh_token where account_id = :a and revoked_at is null")
                .param("a", accountId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from auth.oauth2_authorization where id = :id").param("id", family.toString())
                .query(Integer.class).single()).isZero();

        // in the Inactive tab, with who / when / why; then restored
        JsonNode inactive = body(mvc.perform(get(USERS).param("status", "inactive").param("q", user.get("username").asText())
                .header("Authorization", superAdmin.bearer())));
        assertThat(inactive.get("total").asInt()).isEqualTo(1);
        assertThat(inactive.get("items").get(0).get("status").asText()).isEqualTo("INACTIVE");
        assertThat(inactive.get("items").get(0).get("deactivated").get("reason").asText()).isEqualTo("Left the school");
        assertThat(body(mvc.perform(get(USERS).param("q", user.get("username").asText()).header("Authorization", superAdmin.bearer())))
                .get("total").asInt()).isZero();

        JsonNode restored = body(mvc.perform(post(USERS + "/" + user.get("id").asText() + "/restore")
                .header("Authorization", superAdmin.bearer())).andExpect(status().isOk()));
        assertThat(restored.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(account(user)).containsEntry("status", "active");
        // a deactivated user cannot be edited, only restored
        mvc.perform(delete(USERS + "/" + user.get("id").asText()).header("Authorization", superAdmin.bearer()));
        update(superAdmin, restored, Map.of("name", "X")).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void signOutAndResetActions() throws Exception {
        JsonNode user = newUser(superAdmin, "viewer");
        UUID accountId = (UUID) account(user).get("id");
        String id = user.get("id").asText();

        mvc.perform(post(USERS + "/" + id + "/sign-out").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions").value(0));
        assertThat(account(user).get("sessions_revoked_at")).isNotNull();

        // a new password link replaces the old one
        mvc.perform(post(USERS + "/" + id + "/reset-password").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isAccepted());
        assertThat(jdbc.sql("select count(*) from auth.password_reset_token where account_id = :a and used_at is null")
                .param("a", accountId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from auth.password_reset_token where account_id = :a")
                .param("a", accountId).query(Integer.class).single()).isEqualTo(2);

        // lost phone: the authenticator is removed
        jdbc.sql("update auth.account set totp_secret_enc = 'x', totp_enabled_at = now() where id = :a").param("a", accountId).update();
        assertThat(body(mvc.perform(get(USERS + "/" + id).header("Authorization", superAdmin.bearer())))
                .get("twoFactor").get("enrolled").asBoolean()).isTrue();
        JsonNode reset = body(mvc.perform(post(USERS + "/" + id + "/reset-two-factor").header("Authorization", superAdmin.bearer())));
        assertThat(reset.get("twoFactor").get("enrolled").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ own account, last Super Admin

    @Test
    void nobodyChangesTheirOwnRoleOrDeactivatesThemselves() throws Exception {
        UUID school = newSchool();
        Caller bootstrap = TestJwt.webUser("super_admin", school);
        JsonNode me = newUser(bootstrap, "super_admin");
        newUser(bootstrap, "super_admin");   // another Super Admin, so only the "own account" rule is in play
        Caller myself = TestJwt.webUser("super_admin", school, (UUID) account(me).get("id"));

        update(myself, me, Map.of("role", "viewer")).andExpect(jsonPath("$.code").value("OWN_ACCOUNT"));
        mvc.perform(delete(USERS + "/" + me.get("id").asText()).header("Authorization", myself.bearer()))
                .andExpect(jsonPath("$.code").value("OWN_ACCOUNT"));
        moveRole(myself, List.of(me), "viewer").andExpect(jsonPath("$.code").value("OWN_ACCOUNT"));
        // other changes to your own record are fine
        update(myself, me, Map.of("name", "New name")).andExpect(status().isOk());
    }

    @Test
    void atLeastOneActiveSuperAdminRemains() throws Exception {
        UUID school = newSchool();
        Caller bootstrap = TestJwt.webUser("super_admin", school);   // e.g. the first account, not a web user
        JsonNode first = newUser(bootstrap, "super_admin");

        mvc.perform(delete(USERS + "/" + first.get("id").asText()).header("Authorization", bootstrap.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));
        update(bootstrap, first, Map.of("role", "hr")).andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));
        moveRole(bootstrap, List.of(first), "hr").andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));

        // a second one whose access has ended does not count
        JsonNode expired = newUser(bootstrap, "super_admin");
        jdbc.sql("update web_user set access_expires_on = :d where id = :id")
                .param("d", LocalDate.now(clock).minusDays(1)).param("id", UUID.fromString(expired.get("id").asText())).update();
        mvc.perform(delete(USERS + "/" + first.get("id").asText()).header("Authorization", bootstrap.bearer()))
                .andExpect(jsonPath("$.code").value("LAST_SUPER_ADMIN"));

        // with another active Super Admin it is fine
        newUser(bootstrap, "super_admin");
        mvc.perform(delete(USERS + "/" + first.get("id").asText()).header("Authorization", bootstrap.bearer()))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ roles and their users

    @Test
    void usersMoveBetweenRolesAndARoleInUseCannotBeDeleted() throws Exception {
        UUID school = newSchool();
        Caller admin = TestJwt.webUser("super_admin", school);
        JsonNode frontDesk = createRole(admin, unique("Front desk"));
        JsonNode a = newUser(admin, frontDesk.get("code").asText());
        JsonNode b = newUser(admin, frontDesk.get("code").asText());

        JsonNode roleNow = role(admin, frontDesk.get("id").asText());
        assertThat(roleNow.get("users").asInt()).isEqualTo(2);
        JsonNode members = body(mvc.perform(get(ROLES + "/" + frontDesk.get("id").asText() + "/users").header("Authorization", admin.bearer())));
        assertThat(members).hasSize(2);

        mvc.perform(delete(ROLES + "/" + frontDesk.get("id").asText()).header("Authorization", admin.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));

        // "force logout everywhere": both users of the role
        mvc.perform(post(ROLES + "/" + frontDesk.get("id").asText() + "/force-logout").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.signedOut").value(2));
        assertThat(account(a).get("sessions_revoked_at")).isNotNull();

        moveRole(admin, List.of(a, b), "viewer").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(2));
        assertThat(account(a)).containsEntry("role", "viewer");
        moveRole(admin, List.of(a), "no_such_role").andExpect(status().isBadRequest());

        mvc.perform(delete(ROLES + "/" + frontDesk.get("id").asText()).header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void theDefaultRoleIsGivenToNewUsers() throws Exception {
        UUID school = newSchool();
        Caller admin = TestJwt.webUser("super_admin", school);
        JsonNode hr = roleByCode(admin, "hr");
        assertThat(roleByCode(admin, "viewer").get("defaultRole").asBoolean()).isTrue();

        mvc.perform(put(ROLES + "/" + hr.get("id").asText() + "/default").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.defaultRole").value(true));
        assertThat(roleByCode(admin, "viewer").get("defaultRole").asBoolean()).isFalse();
        JsonNode user = body(create(admin, Map.of("name", "New", "username", unique("new"))).andExpect(status().isCreated()));
        assertThat(user.get("role").get("code").asText()).isEqualTo("hr");
    }

    // ------------------------------------------------------------------ list, isolation, sensitive phone

    @Test
    void theListSearchesFiltersAndStaysInTheSchool() throws Exception {
        UUID school = newSchool();
        Caller admin = TestJwt.webUser("super_admin", school);
        String tag = unique("zz");
        newUser(admin, "hr", "Sophal " + tag);
        newUser(admin, "qr_manager", "Kosal " + tag);
        newUser(admin, "qr_manager", "Navy " + tag);

        JsonNode all = body(mvc.perform(get(USERS).param("q", tag).header("Authorization", admin.bearer())));
        assertThat(all.get("total").asInt()).isEqualTo(3);
        assertThat(all.get("items").get(0).get("name").asText()).startsWith("Kosal");   // by name
        assertThat(body(mvc.perform(get(USERS).param("q", tag).param("role", "qr_manager").header("Authorization", admin.bearer())))
                .get("total").asInt()).isEqualTo(2);
        assertThat(body(mvc.perform(get(USERS).param("q", "%").header("Authorization", admin.bearer()))).get("total").asInt()).isZero();
        mvc.perform(get(USERS).param("size", "1000").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());

        // another school sees none of them
        assertThat(body(mvc.perform(get(USERS).param("q", tag).header("Authorization", superAdmin.bearer()))).get("total").asInt()).isZero();
        mvc.perform(get(USERS + "/" + all.get("items").get(0).get("id").asText()).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePhoneIsASensitiveField() throws Exception {
        JsonNode user = body(create(superAdmin, Map.of("name", "Phone", "username", unique("phone"), "phone", "+855 99 111 222"))
                .andExpect(status().isCreated()));
        Caller noPhones = TestRoles.callerWithRole(mvc, json, unique("Users clerk"), Map.of("users", List.of("view", "edit")));
        JsonNode clerkRole = roleByCode(superAdmin, codeOf(noPhones));
        mvc.perform(put(ROLES + "/" + clerkRole.get("id").asText() + "/sensitive-fields").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salaryBank\":false,\"nationalId\":false,\"phone\":false,\"idScans\":false,\"version\":" + clerkRole.get("version") + "}"))
                .andExpect(status().isOk());

        JsonNode seen = body(mvc.perform(get(USERS + "/" + user.get("id").asText()).header("Authorization", noPhones.bearer())));
        assertThat(seen.get("phone").asText()).isEqualTo("••••");
        // saving the form with the mask keeps the number
        update(noPhones, seen, Map.of("name", "Phone renamed")).andExpect(status().isOk());
        assertThat(account(user)).containsEntry("phone", "+855 99 111 222");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode newUser(Caller caller, String role) throws Exception {
        return newUser(caller, role, "User " + unique(""));
    }

    private JsonNode newUser(Caller caller, String role, String name) throws Exception {
        return body(create(caller, Map.of("name", name, "username", unique("user"), "role", role)).andExpect(status().isCreated()));
    }

    private ResultActions create(Caller caller, Map<String, Object> fields) throws Exception {
        return mvc.perform(post(USERS).header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(fields)));
    }

    /** Saves the whole form: what was read, with these changes. */
    private ResultActions update(Caller caller, JsonNode user, Map<String, Object> changes) throws Exception {
        Map<String, Object> form = new HashMap<>();
        form.put("name", user.get("name").asText());
        form.put("username", user.get("username").asText());
        form.put("phone", user.has("phone") ? user.get("phone").asText() : null);
        form.put("role", user.get("role").get("code").asText());
        form.put("linkedPersonId", user.has("linkedPersonId") ? user.get("linkedPersonId").asText() : null);
        form.put("twoFactorRequired", user.get("twoFactor").get("required").asBoolean());
        form.put("accessExpiresOn", user.has("accessExpiresOn") ? user.get("accessExpiresOn").asText() : null);
        form.put("version", user.get("version").asLong());
        form.putAll(changes);
        return mvc.perform(put(USERS + "/" + user.get("id").asText()).header("Authorization", caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(form)));
    }

    private ResultActions moveRole(Caller caller, List<JsonNode> users, String role) throws Exception {
        return mvc.perform(post(USERS + "/move-role").header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userIds", users.stream().map(u -> u.get("id").asText()).toList(), "role", role))));
    }

    private Map<String, Object> account(JsonNode user) {
        return jdbc.sql("select * from auth.account where web_user_id = :w").param("w", UUID.fromString(user.get("id").asText()))
                .query().singleRow();
    }

    private JsonNode createRole(Caller caller, String name) throws Exception {
        return body(mvc.perform(post(ROLES).header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"en\":\"" + name + "\"}}")).andExpect(status().isCreated()));
    }

    private JsonNode role(Caller caller, String id) throws Exception {
        return body(mvc.perform(get(ROLES + "/" + id).header("Authorization", caller.bearer())));
    }

    private JsonNode roleByCode(Caller caller, String code) throws Exception {
        for (JsonNode role : body(mvc.perform(get(ROLES).header("Authorization", caller.bearer())))) {
            if (role.get("code").asText().equals(code)) {
                return role;
            }
        }
        throw new AssertionError("no role " + code);
    }

    private String codeOf(Caller caller) throws Exception {
        return body(mvc.perform(get("/api/v1/admin/me").header("Authorization", caller.bearer()))).get("role").asText();
    }

    /** A person of the default school, to link web users to. */
    private UUID newPerson() throws Exception {
        return UUID.fromString(body(mvc.perform(post("/api/v1/admin/people").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"km\":\"ឈ្មោះ\",\"en\":\"" + unique("Person ") + "\"},\"role\":\"staff\"}"))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID newSchool() {
        UUID school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Web users school', now(), now())")
                .param("id", school).param("c", "W" + school.toString().substring(0, 8)).update();
        return school;
    }

    private void expectErrors(ResultActions result, String... fieldRules) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> found = new java.util.ArrayList<>();
        problem.get("errors").forEach(e -> found.add(e.get("field").asText() + ":" + e.get("rule").asText()));
        assertThat(found).contains(fieldRules);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
