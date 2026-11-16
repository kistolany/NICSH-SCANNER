package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kh.cmt.auth.device.AppVersions;
import kh.cmt.auth.support.AuthServerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 6.3 on the auth server: one phone per account, the change request from the new phone, the school's rules. */
@AuthServerTest
class DeviceBindingTest {

    static final String PASSWORD = "Staff#Pass1";
    static final UUID SCHOOL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String TOKEN = "X-Device-Change-Token";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;

    @AfterEach
    void defaultRules() {
        jdbc.sql("delete from device_policy where school_id = :s").param("s", SCHOOL).update();
    }

    @Test
    void anotherPhoneAsksForTheChange() throws Exception {
        String user = appAccount();
        String oldPhone = UUID.randomUUID().toString();
        String newPhone = UUID.randomUUID().toString();
        login(user, oldPhone, "Galaxy A54", "1.0.0").andExpect(status().isOk());

        JsonNode refused = body(login(user, newPhone, "iPhone 15", "1.0.0").andExpect(status().isForbidden()));
        assertThat(refused.get("code").asText()).isEqualTo("DEVICE_MISMATCH");
        assertThat(refused.get("linkedDeviceName").asText()).isEqualTo("Galaxy A54");
        String token = refused.get("deviceChangeToken").asText();
        assertThat(token).hasSizeGreaterThan(40);
        assertThat(jdbc.sql("select count(*) from device_change_token where token_hash = :t").param("t", token).query(Long.class).single())
                .as("only a hash is kept").isZero();

        // nothing yet; then the request
        mvc.perform(get("/auth/device/change-request").header(TOKEN, token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.request").doesNotExist());
        JsonNode created = body(send(post("/auth/device/change-request"), token, "{\"reason\":\"new_phone\"}").andExpect(status().isCreated()));
        assertThat(created.get("status").asText()).isEqualTo("pending");
        assertThat(created.get("reason").asText()).isEqualTo("new_phone");
        send(post("/auth/device/change-request"), token, "{\"reason\":\"broken\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_OPEN"));
        var row = jdbc.sql("select old_device_name, new_device_name, new_platform from device_change_request where id = :id")
                .param("id", UUID.fromString(created.get("id").asText())).query().singleRow();
        assertThat(row).containsEntry("old_device_name", "Galaxy A54").containsEntry("new_device_name", "iPhone 15")
                .containsEntry("new_platform", "android");

        // cancel, and the checks
        mvc.perform(delete("/auth/device/change-request").header(TOKEN, token)).andExpect(status().isNoContent());
        assertThat(body(mvc.perform(get("/auth/device/change-request").header(TOKEN, token))).get("request").get("status").asText())
                .isEqualTo("cancelled");
        send(post("/auth/device/change-request"), token, "{\"reason\":\"stolen\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/auth/device/change-request")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(get("/auth/device/change-request").header(TOKEN, "made-up")).andExpect(status().isUnauthorized());

        // an approved change (cmt-api links the new phone): the new phone gets in, the old one is now the other phone
        jdbc.sql("update account set device_id = :d, device_name = 'iPhone 15' where username = :u").param("d", UUID.fromString(newPhone))
                .param("u", user).update();
        login(user, newPhone, "iPhone 15", "1.0.0").andExpect(status().isOk());
        login(user, oldPhone, "Galaxy A54", "1.0.0").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_MISMATCH"));
    }

    @Test
    void anAdminResetLetsTheNextPhoneIn_andTheHistoryNamesIt() throws Exception {
        String user = appAccount();
        login(user, UUID.randomUUID().toString(), "Redmi 9", "1.0.0").andExpect(status().isOk());
        UUID account = accountId(user);
        // what cmt-api's "Reset device" does
        jdbc.sql("update account set device_id = null, device_name = null where id = :a").param("a", account).update();
        jdbc.sql("""
                insert into device_event (id, school_id, account_id, kind, old_device_name, reason, at)
                values (:id, :s, :a, 'RESET', 'Redmi 9', 'Lost phone', now())""")
                .param("id", UUID.randomUUID()).param("s", SCHOOL).param("a", account).update();

        login(user, UUID.randomUUID().toString(), "Oppo A78", "1.0.0").andExpect(status().isOk());
        assertThat(jdbc.sql("select new_device_name from device_event where account_id = :a and kind = 'RESET'").param("a", account)
                .query(String.class).single()).isEqualTo("Oppo A78");
        var device = jdbc.sql("select device_name, device_platform, device_os, device_app_version, device_public_key, device_last_seen_at "
                + "from account where id = :a").param("a", account).query().singleRow();
        assertThat(device).containsEntry("device_name", "Oppo A78").containsEntry("device_platform", "android")
                .containsEntry("device_os", "Android 14").containsEntry("device_app_version", "1.0.0").containsEntry("device_public_key", "pk-1");
        assertThat(device.get("device_last_seen_at")).isNotNull();
    }

    @Test
    void theSchoolsRules() throws Exception {
        // the minimum app version
        jdbc.sql("insert into device_policy (school_id, min_app_version) values (:s, '1.2.0')").param("s", SCHOOL).update();
        String user = appAccount();
        String phone = UUID.randomUUID().toString();
        login(user, phone, "Pixel 9a", "1.1.9").andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.code").value("UPDATE_REQUIRED")).andExpect(jsonPath("$.minVersion").value("1.2.0"));
        login(user, phone, "Pixel 9a", "1.10.0").andExpect(status().isOk());

        // no change requests: no token
        jdbc.sql("update device_policy set change_requests = false where school_id = :s").param("s", SCHOOL).update();
        JsonNode refused = body(login(user, UUID.randomUUID().toString(), "Other", "1.2.0").andExpect(status().isForbidden()));
        assertThat(refused.get("deviceChangeToken").isNull()).isTrue();

        // automatic rebind: the linked phone was not used for more than 7 days
        jdbc.sql("update device_policy set change_requests = true, auto_rebind_days = 7 where school_id = :s").param("s", SCHOOL).update();
        jdbc.sql("update account set device_last_seen_at = :t where username = :u").param("t", OffsetDateTime.now().minusDays(8))
                .param("u", user).update();
        String newPhone = UUID.randomUUID().toString();
        login(user, newPhone, "Galaxy S24", "1.2.0").andExpect(status().isOk());
        assertThat(jdbc.sql("select kind from device_event where account_id = :a order by at desc limit 1").param("a", accountId(user))
                .query(String.class).single()).isEqualTo("AUTO_REBIND");
        // a phone used yesterday is not taken over
        login(user, UUID.randomUUID().toString(), "Thief", "1.2.0").andExpect(status().isForbidden());

        // several phones allowed: every login moves the account, the old phone's session ends
        jdbc.sql("update device_policy set one_device = false, auto_rebind_days = null where school_id = :s").param("s", SCHOOL).update();
        JsonNode first = body(login(user, newPhone, "Galaxy S24", "1.2.0"));
        login(user, UUID.randomUUID().toString(), "Tablet", "1.2.0").andExpect(status().isOk());
        refresh(first.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    void aBlockedPhoneCannotBeUsed_andARefreshMarksThePhoneSeen() throws Exception {
        String user = appAccount();
        String phone = UUID.randomUUID().toString();
        JsonNode tokens = body(login(user, phone, "Stolen one", "1.0.0"));
        jdbc.sql("update account set device_last_seen_at = null where username = :u").param("u", user).update();
        refresh(tokens.get("refreshToken").asText()).andExpect(status().isOk());
        assertThat(jdbc.sql("select device_last_seen_at from account where username = :u").param("u", user).query(OffsetDateTime.class).single())
                .isNotNull();

        jdbc.sql("insert into device_block (device_id, school_id, reason, blocked_at) values (:d, :s, 'Stolen phone', now())")
                .param("d", UUID.fromString(phone)).param("s", SCHOOL).update();
        login(user, phone, "Stolen one", "1.0.0").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_BLOCKED"));
        login(appAccount(), phone, "Stolen one", "1.0.0").andExpect(jsonPath("$.code").value("DEVICE_BLOCKED"));   // any account
    }

    @Test
    void versionsCompareAsNumbers() {
        assertThat(AppVersions.older("1.9.2", "1.10.0")).isTrue();
        assertThat(AppVersions.older("1.10.0", "1.9.2")).isFalse();
        assertThat(AppVersions.older("1.2", "1.2.0")).isFalse();
        assertThat(AppVersions.older("1.2.0-beta", "1.2.0")).isFalse();
        assertThat(AppVersions.older(null, "1.0.0")).isTrue();
        assertThat(AppVersions.older("0.1.0", null)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private String appAccount() {
        String username = "dev." + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into account (id, school_id, username, password_hash, type, person_id, status, role, created_at, updated_at)
                values (:id, :s, :u, :hash, 'app', :p, 'active', 'staff', :now, :now)""")
                .param("id", UUID.randomUUID()).param("s", SCHOOL).param("u", username).param("hash", encoder.encode(PASSWORD))
                .param("p", UUID.randomUUID()).param("now", OffsetDateTime.now()).update();
        return username;
    }

    private UUID accountId(String user) {
        return jdbc.sql("select id from account where username = :u").param("u", user).query(UUID.class).single();
    }

    private ResultActions login(String username, String deviceId, String deviceName, String appVersion) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("deviceId", deviceId);
        body.put("deviceName", deviceName);
        body.put("platform", "Android");
        body.put("appVersion", appVersion);
        body.put("osVersion", "Android 14");
        body.put("publicKey", "pk-1");
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", token))));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token, String body)
            throws Exception {
        return mvc.perform(request.header(TOKEN, token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
