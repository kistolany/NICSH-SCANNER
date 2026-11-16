package kh.cmt.qrlocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kh.cmt.qrlocation.api.QrCodes;
import kh.cmt.qrlocation.api.QrCodes.Result;
import kh.cmt.shared.security.SchoolOverride;
import kh.cmt.support.ApiTest;
import kh.cmt.support.QrImages;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 6.2 "done when": a replaced QR is accepted for 5 minutes, then {@code QR_EXPIRED}. */
@ApiTest
class GateQrTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired QrCodes qrCodes;

    private UUID school;
    private Caller admin;
    private String gate;

    @BeforeEach
    void aGate() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'QR school', now(), now())")
                .param("id", school).param("c", "Q" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        String area = id(send(post("/api/v1/admin/areas"), "{\"name\":\"Main campus\",\"latitude\":11.5564,\"longitude\":104.9282,\"radiusMeters\":120}"));
        gate = id(send(post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + area + "\"}"));
    }

    @Test
    void aReplacedQrIsAcceptedForFiveMinutesThenExpired() throws Exception {
        JsonNode first = body(mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer()))
                .andExpect(status().isCreated()));
        assertThat(first.get("qr").get("version").asInt()).isEqualTo(1);
        assertThat(first.get("qr").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.has("replaced")).isFalse();
        String v1 = printedPayload(1);
        assertThat(v1).startsWith("cmt://gate/" + gate + "?v=1&sig=");
        assertThat(check(v1, clock.instant()).result()).isEqualTo(Result.VALID);

        Instant replacedAt = clock.instant();
        JsonNode second = body(mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer()))
                .andExpect(status().isCreated()));
        assertThat(second.get("qr").get("version").asInt()).isEqualTo(2);
        JsonNode replaced = second.get("replaced");
        assertThat(replaced.get("version").asInt()).isEqualTo(1);
        assertThat(replaced.get("status").asText()).isEqualTo("EXPIRING");
        assertThat(replaced.get("secondsLeft").asLong()).isBetween(290L, 300L);
        Instant ends = Instant.parse(replaced.get("expiresAt").asText());
        assertThat(Duration.between(replacedAt, ends)).isBetween(Duration.ofSeconds(299), Duration.ofSeconds(301));

        // done-when: the old QR works until five minutes are up, then QR_EXPIRED; the new one works
        QrCodes.Check before = check(v1, ends.minusSeconds(1));
        assertThat(before.result()).isEqualTo(Result.VALID);
        assertThat(before.gateId().toString()).isEqualTo(gate);
        assertThat(check(v1, ends.plusSeconds(1)).result()).isEqualTo(Result.QR_EXPIRED);
        String v2 = printedPayload(null);
        assertThat(v2).contains("?v=2&");
        assertThat(check(v2, ends.plus(Duration.ofDays(30))).result()).isEqualTo(Result.VALID);
        // the expired one cannot be printed any more (once it is past); the gate card shows v2
        assertThat(body(mvc.perform(get("/api/v1/admin/gates/" + gate).header("Authorization", admin.bearer()))).get("qr").get("version").asInt())
                .isEqualTo(2);

        // history, newest first
        JsonNode history = body(mvc.perform(get("/api/v1/admin/qr/history").header("Authorization", admin.bearer())));
        assertThat(history.get("items")).extracting(r -> r.get("version").asInt() + ":" + r.get("status").asText())
                .containsExactly("2:ACTIVE", "1:EXPIRING");
        assertThat(history.get("items").get(1).get("expiredReason").asText()).isEqualTo("REPLACED");
        assertThat(history.get("items").get(0).get("gate").get("name").asText()).isEqualTo("Main gate");

        // "Expire now": v1 stops at once
        String v1Id = history.get("items").get(1).get("id").asText();
        JsonNode expired = body(mvc.perform(post("/api/v1/admin/qr/" + v1Id + "/expire").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()));
        assertThat(expired.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(expired.get("expiredReason").asText()).isEqualTo("MANUAL");
        assertThat(check(v1, clock.instant()).result()).isEqualTo(Result.QR_EXPIRED);
        mvc.perform(get("/api/v1/admin/gates/" + gate + "/qr/print.pdf").param("version", "1").header("Authorization", admin.bearer()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void forgedUnknownAndForeignQrsAreInvalid() throws Exception {
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer())).andExpect(status().isCreated());
        String real = printedPayload(1);
        Instant now = clock.instant();
        String forged = real.substring(0, real.length() - 4) + (real.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThat(check(forged, now).result()).isEqualTo(Result.QR_INVALID);
        assertThat(check(real.replace("?v=1&", "?v=2&"), now).result()).isEqualTo(Result.QR_INVALID);   // a version never made
        assertThat(check("cmt://gate/" + UUID.randomUUID() + "?v=1&sig=AAAAAAAAAAAAAAAAAAAAAA", now).result()).isEqualTo(Result.QR_INVALID);
        assertThat(check("https://example.com/menu", now).result()).isEqualTo(Result.QR_INVALID);
        // another school's scan of this QR
        assertThat(SchoolOverride.callAs(UUID.randomUUID(), () -> qrCodes.verify(real, now)).result()).isEqualTo(Result.QR_INVALID);
    }

    @Test
    void aRetiredGateStopsItsQrAtOnce() throws Exception {
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer())).andExpect(status().isCreated());
        String v1 = printedPayload(1);
        mvc.perform(delete("/api/v1/admin/gates/" + gate).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        assertThat(check(v1, clock.instant()).result()).isEqualTo(Result.QR_EXPIRED);
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer())).andExpect(status().isConflict());
        // restored: no QR until a new one is made
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/restore").header("Authorization", admin.bearer())).andExpect(status().isOk());
        assertThat(body(mvc.perform(get("/api/v1/admin/gates/" + gate).header("Authorization", admin.bearer()))).has("qr")).isFalse();
        assertThat(check(v1, clock.instant()).result()).isEqualTo(Result.QR_EXPIRED);
        JsonNode next = body(mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer()))
                .andExpect(status().isCreated()));
        assertThat(next.get("qr").get("version").asInt()).isEqualTo(2);
        assertThat(next.has("replaced")).isFalse();
    }

    @Test
    void whoMayAndTheSheet() throws Exception {
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", admin.bearer())).andExpect(status().isCreated());
        // the PDF to print: inline, never cached
        mvc.perform(get("/api/v1/admin/gates/" + gate + "/qr/print.pdf").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline; filename=\"QR_Main-gate_v1.pdf\"")));
        mvc.perform(get("/api/v1/admin/gates/" + gate + "/qr/print.png").header("Authorization", admin.bearer()))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"QR_Main-gate_v1.png\"")));
        // the Viewer sees the history but cannot print or make QRs; the QR Manager can
        Caller viewer = TestJwt.webUser("viewer", school);
        mvc.perform(get("/api/v1/admin/qr/history").header("Authorization", viewer.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/gates/" + gate + "/qr/print.pdf").header("Authorization", viewer.bearer())).andExpect(status().isForbidden());
        Caller qrManager = TestJwt.webUser("qr_manager", school);
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/qr").header("Authorization", qrManager.bearer())).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/admin/gates/" + gate + "/qr/print.pdf").header("Authorization", qrManager.bearer())).andExpect(status().isOk());
        // nothing printable for a gate of another school, or one without a QR
        mvc.perform(get("/api/v1/admin/gates/" + UUID.randomUUID() + "/qr/print.pdf").header("Authorization", admin.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/qr/history").param("size", "500").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    /** The QR on the downloaded sheet, read back like a phone would. */
    private String printedPayload(Integer version) throws Exception {
        var request = get("/api/v1/admin/gates/" + gate + "/qr/print.png").header("Authorization", admin.bearer());
        if (version != null) {
            request.param("version", version.toString());
        }
        return QrImages.read(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private QrCodes.Check check(String payload, Instant at) {
        return SchoolOverride.callAs(school, () -> qrCodes.verify(payload, at));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
