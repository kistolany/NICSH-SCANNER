package kh.cmt.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 6.3, cmt-api side (Admin Web › Devices): the phones the auth server linked (written here the way its login does),
 * change requests approved / rejected, reset, block, history, settings, and the app's "My device". The auth server's
 * side (refusing another phone, the request from the new phone, logging in after a reset) is tested there.
 */
@ApiTest
class DevicesTest {

    static final String DEVICES = "/api/v1/admin/devices";
    static final String INBOX = "/api/v1/admin/approvals";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private UUID school;
    private Caller admin;
    private Staff sokha;   // linked phone
    private Staff dara;    // login, no phone yet
    private String vanna;  // no login at all

    record Staff(UUID person, UUID account, UUID phone) {
    }

    @BeforeEach
    void aSchoolWithPhones() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Device school', now(), now())")
                .param("id", school).param("c", "D" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String tag = UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9]", "x");
        sokha = staff("Sokha " + tag);
        dara = staff("Dara " + tag);
        vanna = id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"Vanna " + tag + "\"},\"role\":\"staff\"}"));
        linkPhone(sokha, "Galaxy A54", "android", "1.0.0", OffsetDateTime.now().minusHours(2));
    }

    // ------------------------------------------------------------------ done-when (cmt-api half): reset lets the next phone in

    @Test
    void theTableAndAResetThatLetsTheNextPhoneIn() throws Exception {
        JsonNode page = body(mvc.perform(get(DEVICES).header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(3);
        JsonNode s = row(page, sokha.person());
        assertThat(s.get("status").asText()).isEqualTo("LINKED");
        assertThat(s.get("device").get("name").asText()).isEqualTo("Galaxy A54");
        assertThat(s.get("device").get("platform").asText()).isEqualTo("android");
        assertThat(s.get("device").get("idle").asBoolean()).isFalse();
        assertThat(row(page, dara.person()).get("status").asText()).isEqualTo("NONE");
        assertThat(row(page, dara.person()).get("hasLogin").asBoolean()).isTrue();
        assertThat(row(page, UUID.fromString(vanna)).get("hasLogin").asBoolean()).isFalse();
        assertThat(page.get("counts").get("linked").asInt()).isEqualTo(1);
        assertThat(page.get("counts").get("none").asInt()).isEqualTo(2);

        // filters: the "No device" tab, search by phone, platform
        assertThat(body(mvc.perform(get(DEVICES).param("tab", "none").header("Authorization", admin.bearer()))).get("total").asInt()).isEqualTo(2);
        assertThat(body(mvc.perform(get(DEVICES).param("q", "galaxy").header("Authorization", admin.bearer()))).get("items")).hasSize(1);
        assertThat(body(mvc.perform(get(DEVICES).param("platform", "ios").header("Authorization", admin.bearer()))).get("items")).isEmpty();

        // reset: the phone is unlinked, the history names it, the person is signed out
        JsonNode reset = body(send(post(DEVICES + "/" + sokha.person() + "/reset"), "{\"reason\":\"Lost phone\"}").andExpect(status().isOk()));
        assertThat(reset.get("status").asText()).isEqualTo("NONE");
        assertThat(jdbc.sql("select device_id from auth.account where id = :a").param("a", sokha.account()).query(UUID.class).optional()).isEmpty();
        assertThat(jdbc.sql("select sessions_revoked_at from auth.account where id = :a").param("a", sokha.account())
                .query(OffsetDateTime.class).single()).isNotNull();
        JsonNode history = body(mvc.perform(get(DEVICES + "/history").param("kind", "reset").header("Authorization", admin.bearer())));
        assertThat(history.get("items")).singleElement().satisfies(e -> {
            assertThat(e.get("kind").asText()).isEqualTo("RESET");
            assertThat(e.get("oldDevice").asText()).isEqualTo("Galaxy A54");
            assertThat(e.get("reason").asText()).isEqualTo("Lost phone");
            assertThat(e.get("person").get("id").asText()).isEqualTo(sokha.person().toString());
        });
        assertThat(body(mvc.perform(get(DEVICES).header("Authorization", admin.bearer()))).get("counts").get("resetsThisMonth").asInt()).isEqualTo(1);
        // nothing left to reset; no login at all
        send(post(DEVICES + "/" + sokha.person() + "/reset"), "{}").andExpect(status().isConflict());
        send(post(DEVICES + "/" + vanna + "/reset"), "{}").andExpect(status().isConflict());
    }

    @Test
    void aChangeRequestIsSeenThenApproved_andAnotherRejected() throws Exception {
        UUID newPhone = UUID.randomUUID();
        UUID request = changeRequest(sokha, newPhone, "iPhone 15", "new_phone");
        assertThat(row(body(mvc.perform(get(DEVICES).header("Authorization", admin.bearer()))), sokha.person()).get("status").asText())
                .isEqualTo("WAITING");

        JsonNode open = body(mvc.perform(get(DEVICES + "/requests").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(open).singleElement().satisfies(r -> {
            assertThat(r.get("oldDevice").asText()).isEqualTo("Galaxy A54");
            assertThat(r.get("newDevice").get("name").asText()).isEqualTo("iPhone 15");
            assertThat(r.get("reason").asText()).isEqualTo("new_phone");
        });
        assertThat(requestStatus(request)).isEqualTo("seen");   // the phone shows "Seen by admin"

        JsonNode approved = body(mvc.perform(post(DEVICES + "/requests/" + request + "/approve").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("approved");
        assertThat(jdbc.sql("select device_id from auth.account where id = :a").param("a", sokha.account()).query(UUID.class).single())
                .isEqualTo(newPhone);
        assertThat(jdbc.sql("select sessions_revoked_at from auth.account where id = :a").param("a", sokha.account())
                .query(OffsetDateTime.class).single()).isNotNull();   // the old phone is signed out
        mvc.perform(post(DEVICES + "/requests/" + request + "/approve").header("Authorization", admin.bearer())).andExpect(status().isConflict());
        JsonNode history = body(mvc.perform(get(DEVICES + "/history").header("Authorization", admin.bearer())));
        assertThat(history.get("items").get(0).get("kind").asText()).isEqualTo("CHANGE_APPROVED");
        assertThat(history.get("items").get(0).get("newDevice").asText()).isEqualTo("iPhone 15");

        // dara asks too (a first phone was never linked); rejected with a reason
        UUID second = changeRequest(dara, UUID.randomUUID(), "Redmi", "broken");
        send(post(DEVICES + "/requests/" + second + "/reject"), "{}").andExpect(status().isBadRequest());
        JsonNode rejected = body(send(post(DEVICES + "/requests/" + second + "/reject"), "{\"reason\":\"Come to the office\"}")
                .andExpect(status().isOk()));
        assertThat(rejected.get("status").asText()).isEqualTo("rejected");
        assertThat(rejected.get("rejectionReason").asText()).isEqualTo("Come to the office");
        assertThat(body(mvc.perform(get(DEVICES + "/requests").param("status", "all").header("Authorization", admin.bearer())))).hasSize(2);
        // HR has Approvals; the Viewer has not
        mvc.perform(post(DEVICES + "/requests/" + UUID.randomUUID() + "/approve").header("Authorization", TestJwt.webUser("viewer", school).bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void changeRequests_areInTheOneApprovalsInbox_andDecidedThereAtOnce() throws Exception {
        UUID newPhone = UUID.randomUUID();
        UUID request = changeRequest(sokha, newPhone, "iPhone 15", "new_phone");
        UUID second = changeRequest(dara, UUID.randomUUID(), "Redmi", "broken");
        JsonNode inbox = body(mvc.perform(get(INBOX + "?group=device").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(inbox.get("groups").get("device").asInt()).isEqualTo(2);
        assertThat(inbox.get("counts").get("DEVICE_CHANGE").asInt()).isEqualTo(2);
        JsonNode first = inbox.get("items").get(0);
        assertThat(List.of(first.get("id").asText(), first.get("type").asText(), first.get("group").asText(), first.get("reason").asText(),
                first.get("details").get("oldDevice").asText(), first.get("details").get("newDevice").asText(), first.get("canDecide").asText()))
                .containsExactly(request.toString(), "DEVICE_CHANGE", "device", "New phone", "Galaxy A54", "iPhone 15", "true");

        // decided at once (no Undo): the new phone is linked
        JsonNode approved = body(mvc.perform(post(INBOX + "/" + request + "/approve").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.has("undoUntil")).isFalse();
        assertThat(jdbc.sql("select device_id from auth.account where id = :a").param("a", sokha.account()).query(UUID.class).single()).isEqualTo(newPhone);
        assertThat(body(mvc.perform(post(INBOX + "/" + request + "/approve").header("Authorization", admin.bearer())).andExpect(status().isConflict()))
                .get("code").asText()).isEqualTo("ALREADY_DECIDED");
        mvc.perform(post(INBOX + "/" + request + "/undo").header("Authorization", admin.bearer())).andExpect(status().isNotFound());

        send(post(INBOX + "/" + second + "/reject"), "{\"comment\":\"Come to the office\"}").andExpect(status().isOk());
        assertThat(requestStatus(second)).isEqualTo("rejected");
        JsonNode done = body(mvc.perform(get(INBOX + "?status=done&group=device").header("Authorization", admin.bearer())));
        assertThat(done.get("items")).extracting(i -> i.get("status").asText() + " " + i.path("comment").asText("-"))
                .containsExactly("REJECTED Come to the office", "APPROVED -");
        assertThat(body(mvc.perform(get(INBOX).header("Authorization", admin.bearer()))).get("total").asInt()).isZero();
    }

    @Test
    void blockAndUnblock_andTheSettings() throws Exception {
        send(post(DEVICES + "/" + sokha.person() + "/block"), "{}").andExpect(status().isBadRequest());   // a reason is required
        JsonNode blocked = body(send(post(DEVICES + "/" + sokha.person() + "/block"), "{\"reason\":\"Stolen phone\"}").andExpect(status().isOk()));
        assertThat(blocked.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.get("block").get("reason").asText()).isEqualTo("Stolen phone");
        assertThat(jdbc.sql("select count(*) from auth.device_block where device_id = :d").param("d", sokha.phone()).query(Long.class).single())
                .isEqualTo(1);
        send(post(DEVICES + "/" + dara.person() + "/block"), "{\"reason\":\"Lost phone\"}").andExpect(status().isConflict());   // no phone
        JsonNode unblocked = body(mvc.perform(post(DEVICES + "/" + sokha.person() + "/unblock").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()));
        assertThat(unblocked.get("status").asText()).isEqualTo("LINKED");
        assertThat(body(mvc.perform(get(DEVICES + "/history").param("kind", "BLOCKED").header("Authorization", admin.bearer()))).get("items"))
                .hasSize(1);

        // settings: the defaults, then a minimum app version that makes Sokha's phone outdated
        JsonNode settings = body(mvc.perform(get(DEVICES + "/settings").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(settings.get("oneDevice").asBoolean()).isTrue();
        assertThat(settings.get("changeRequests").asBoolean()).isTrue();
        assertThat(settings.get("autoRebindDays").isNull()).isTrue();
        assertThat(settings.get("version").asInt()).isZero();
        String body = "{\"oneDevice\":true,\"changeRequests\":true,\"autoRebindDays\":7,\"minAppVersion\":\"1.2.0\",\"blockRooted\":true,"
                + "\"blockMockLocation\":false,\"version\":0}";
        JsonNode saved = body(send(put(DEVICES + "/settings"), body).andExpect(status().isOk()));
        assertThat(saved.get("version").asInt()).isEqualTo(1);
        assertThat(saved.get("minAppVersion").asText()).isEqualTo("1.2.0");
        send(put(DEVICES + "/settings"), body).andExpect(status().isConflict());   // stale version
        JsonNode bad = body(send(put(DEVICES + "/settings"), "{\"autoRebindDays\":14,\"minAppVersion\":\"one\",\"version\":1}")
                .andExpect(status().isBadRequest()));
        assertThat(bad.get("errors")).extracting(e -> e.get("field").asText() + ":" + e.get("rule").asText())
                .contains("autoRebindDays:OneOf", "minAppVersion:Pattern", "oneDevice:NotNull");
        JsonNode page = body(mvc.perform(get(DEVICES).param("version", "old").header("Authorization", admin.bearer())));
        assertThat(page.get("minAppVersion").asText()).isEqualTo("1.2.0");
        assertThat(page.get("items")).singleElement().satisfies(r -> assertThat(r.get("device").get("outdated").asBoolean()).isTrue());
        // a Viewer reads, cannot change
        mvc.perform(get(DEVICES).header("Authorization", TestJwt.webUser("viewer", school).bearer())).andExpect(status().isOk());
        mvc.perform(post(DEVICES + "/" + sokha.person() + "/reset").header("Authorization", TestJwt.webUser("viewer", school).bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void theAppSeesAndDescribesItsPhone() throws Exception {
        Caller onPhone = TestJwt.withDevice("staff", school, sokha.account(), sokha.person(), sokha.phone());
        JsonNode mine = body(mvc.perform(get("/api/v1/app/me/device").header("Authorization", onPhone.bearer())).andExpect(status().isOk()));
        assertThat(mine.get("name").asText()).isEqualTo("Galaxy A54");
        assertThat(mine.has("linkedSince")).isTrue();

        JsonNode described = body(mvc.perform(post("/api/v1/app/device/register").header("Authorization", onPhone.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"appVersion\":\"1.3.0\",\"osVersion\":\"Android 15\",\"publicKey\":\"MFkwEwYH…\",\"rooted\":false}"))
                .andExpect(status().isOk()));
        assertThat(described.get("appVersion").asText()).isEqualTo("1.3.0");
        assertThat(described.get("os").asText()).isEqualTo("Android 15");
        assertThat(jdbc.sql("select device_public_key from auth.account where id = :a").param("a", sokha.account()).query(String.class).single())
                .isEqualTo("MFkwEwYH…");

        // from another phone; with no phone linked; a blocked phone
        Caller other = TestJwt.withDevice("staff", school, sokha.account(), sokha.person(), UUID.randomUUID());
        mvc.perform(post("/api/v1/app/device/register").header("Authorization", other.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/app/me/device").header("Authorization", TestJwt.withDevice("staff", school, dara.account(), dara.person(), null).bearer()))
                .andExpect(status().isNotFound());
        send(post(DEVICES + "/" + sokha.person() + "/block"), "{\"reason\":\"Suspicious activity\"}").andExpect(status().isOk());
        JsonNode refused = body(mvc.perform(post("/api/v1/app/device/register").header("Authorization", onPhone.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden()));
        assertThat(refused.get("code").asText()).isEqualTo("DEVICE_BLOCKED");
    }

    // ------------------------------------------------------------------ helpers

    /** A person with an app login (as Add person makes it). */
    private Staff staff(String nameEn) throws Exception {
        JsonNode created = body(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + nameEn + "\"},\"role\":\"staff\",\"login\":{}}")
                .andExpect(status().isCreated()));
        UUID person = UUID.fromString(created.get("id").asText());
        UUID account = jdbc.sql("select id from auth.account where person_id = :p and type = 'app'").param("p", person).query(UUID.class).single();
        return new Staff(person, account, UUID.randomUUID());
    }

    /** What the auth server's login does with a first phone. */
    private void linkPhone(Staff s, String name, String platform, String version, OffsetDateTime lastSeen) {
        jdbc.sql("""
                update auth.account set device_id = :d, device_name = :n, device_platform = :p, device_app_version = :v,
                       device_bound_at = :t, device_last_seen_at = :t where id = :a""")
                .param("d", s.phone()).param("n", name).param("p", platform).param("v", version).param("t", lastSeen).param("a", s.account()).update();
    }

    /** What the auth server's POST /auth/device/change-request does. */
    private UUID changeRequest(Staff s, UUID newPhone, String newName, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into auth.device_change_request (id, school_id, account_id, status, reason, old_device_id, old_device_name,
                                                        new_device_id, new_device_name, new_platform, created_at)
                select :id, :s, a.id, 'pending', :r, a.device_id, a.device_name, :nd, :nn, 'ios', now() from auth.account a where a.id = :a""")
                .param("id", id).param("s", school).param("r", reason).param("nd", newPhone).param("nn", newName).param("a", s.account()).update();
        return id;
    }

    private String requestStatus(UUID request) {
        return jdbc.sql("select status from auth.device_change_request where id = :id").param("id", request).query(String.class).single();
    }

    private static JsonNode row(JsonNode page, UUID person) {
        for (JsonNode r : page.get("items")) {
            if (r.get("person").get("id").asText().equals(person.toString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + person);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
