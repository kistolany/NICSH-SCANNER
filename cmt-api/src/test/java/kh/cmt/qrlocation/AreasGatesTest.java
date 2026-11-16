package kh.cmt.qrlocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import kh.cmt.qrlocation.api.Locations;
import kh.cmt.qrlocation.domain.SchoolArea;
import kh.cmt.shared.security.SchoolOverride;
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

/** Step 6.1: school areas (centre + radius, several) and gates in them; where a point is, for the scan. */
@ApiTest
class AreasGatesTest {

    /** CMT School in the design: 11.5564° N, 104.9282° E. */
    static final double LAT = 11.5564;
    static final double LNG = 104.9282;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Locations locations;

    private UUID school;
    private Caller admin;

    @BeforeEach
    void aSchool() {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Area school', now(), now())")
                .param("id", school).param("c", "A" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
    }

    @Test
    void areasAndGatesLikeTheDesign() throws Exception {
        JsonNode main = body(send(admin, post("/api/v1/admin/areas"),
                "{\"name\":\" Main  campus \",\"latitude\":11.55640049,\"longitude\":104.9282,\"radiusMeters\":120}").andExpect(status().isCreated()));
        assertThat(main.get("name").asText()).isEqualTo("Main campus");
        assertThat(main.get("latitude").decimalValue()).isEqualByComparingTo("11.556400");   // 6 decimals kept
        assertThat(main.get("radiusMeters").asInt()).isEqualTo(120);
        assertThat(main.get("gates").asInt()).isZero();
        String field = id(send(admin, post("/api/v1/admin/areas"),
                "{\"name\":\"Sports field\",\"latitude\":11.5580,\"longitude\":104.9300,\"radiusMeters\":60}"));

        JsonNode gate = body(send(admin, post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + main.get("id").asText() + "\"}")
                .andExpect(status().isCreated()));
        assertThat(gate.get("area").get("name").asText()).isEqualTo("Main campus");
        send(admin, post("/api/v1/admin/gates"), "{\"name\":\"Back gate\",\"areaId\":\"" + main.get("id").asText() + "\"}").andExpect(status().isCreated());

        JsonNode list = body(mvc.perform(get("/api/v1/admin/areas").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(list).extracting(a -> a.get("name").asText() + ":" + a.get("gates").asInt()).containsExactly("Main campus:2", "Sports field:0");
        assertThat(body(mvc.perform(get("/api/v1/admin/gates").header("Authorization", admin.bearer()))))
                .extracting(g -> g.get("name").asText()).containsExactly("Back gate", "Main gate");

        // the radius slider: change the radius with the version read
        JsonNode wider = body(send(admin, put("/api/v1/admin/areas/" + main.get("id").asText()),
                "{\"name\":\"Main campus\",\"latitude\":11.5564,\"longitude\":104.9282,\"radiusMeters\":150,\"version\":" + main.get("version") + "}")
                .andExpect(status().isOk()));
        assertThat(wider.get("radiusMeters").asInt()).isEqualTo(150);
        send(admin, put("/api/v1/admin/areas/" + main.get("id").asText()),
                "{\"name\":\"Main campus\",\"latitude\":11.5564,\"longitude\":104.9282,\"radiusMeters\":160,\"version\":" + main.get("version") + "}")
                .andExpect(status().isConflict());   // stale version

        // a gate moves to the sports field
        send(admin, put("/api/v1/admin/gates/" + gate.get("id").asText()), "{\"name\":\"Field gate\",\"areaId\":\"" + field + "\",\"version\":"
                + gate.get("version") + "}").andExpect(status().isOk());
        assertThat(body(mvc.perform(get("/api/v1/admin/gates/" + gate.get("id").asText()).header("Authorization", admin.bearer())))
                .get("area").get("name").asText()).isEqualTo("Sports field");
    }

    @Test
    void checksNamesAndWhatIsInUse() throws Exception {
        JsonNode bad = body(send(admin, post("/api/v1/admin/areas"), "{\"name\":\" \",\"latitude\":91,\"longitude\":-181,\"radiusMeters\":10}")
                .andExpect(status().isBadRequest()));
        assertThat(bad.get("errors")).extracting(e -> e.get("field").asText() + ":" + e.get("rule").asText())
                .containsExactlyInAnyOrder("name:NotBlank", "latitude:Range", "longitude:Range", "radiusMeters:Range");
        JsonNode missing = body(send(admin, post("/api/v1/admin/areas"), "{\"name\":\"X\"}").andExpect(status().isBadRequest()));
        assertThat(missing.get("errors")).extracting(e -> e.get("field").asText() + ":" + e.get("rule").asText())
                .containsExactlyInAnyOrder("latitude:NotNull", "longitude:NotNull", "radiusMeters:NotNull");

        String main = id(send(admin, post("/api/v1/admin/areas"), area("Main campus", 120)));
        send(admin, post("/api/v1/admin/areas"), area("main CAMPUS", 80)).andExpect(status().isConflict());   // NAME_TAKEN
        JsonNode noArea = body(send(admin, post("/api/v1/admin/gates"), "{\"name\":\"Gate\",\"areaId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest()));
        assertThat(noArea.get("errors").get(0).get("rule").asText()).isEqualTo("UnknownArea");
        String gate = id(send(admin, post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + main + "\"}"));

        // an area with an active gate stays; retire the gate first
        assertThat(body(mvc.perform(delete("/api/v1/admin/areas/" + main).header("Authorization", admin.bearer())).andExpect(status().isConflict()))
                .get("code").asText()).isEqualTo("IN_USE");
        mvc.perform(delete("/api/v1/admin/gates/" + gate).param("reason", "Closed for works").header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/areas/" + main).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        JsonNode inactive = body(mvc.perform(get("/api/v1/admin/gates").param("status", "inactive").header("Authorization", admin.bearer())));
        assertThat(inactive).singleElement().satisfies(g -> {
            assertThat(g.get("deleteReason").asText()).isEqualTo("Closed for works");
            assertThat(g.get("area").get("inactive").asBoolean()).isTrue();
        });
        // a gate comes back only into an active area; the retired name is free again
        mvc.perform(post("/api/v1/admin/gates/" + gate + "/restore").header("Authorization", admin.bearer())).andExpect(status().isConflict());
        String newMain = id(send(admin, post("/api/v1/admin/areas"), area("Main campus", 100)));
        mvc.perform(post("/api/v1/admin/areas/" + main + "/restore").header("Authorization", admin.bearer())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/areas/" + newMain + "/restore").header("Authorization", admin.bearer())).andExpect(status().isOk());

        // forever: Super Admin, inactive only, and not while a gate points at it
        mvc.perform(delete("/api/v1/admin/areas/" + newMain + "/forever").header("Authorization", admin.bearer()))
                .andExpect(status().isConflict());   // NOT_INACTIVE
        mvc.perform(delete("/api/v1/admin/areas/" + main + "/forever").header("Authorization", admin.bearer()))
                .andExpect(status().isConflict());   // IN_USE: the inactive gate still belongs to it
        mvc.perform(delete("/api/v1/admin/gates/" + gate + "/forever").header("Authorization", TestJwt.webUser("qr_manager", school).bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/gates/" + gate + "/forever").header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/areas/" + main + "/forever").header("Authorization", admin.bearer())).andExpect(status().isNoContent());
    }

    @Test
    void whoMay() throws Exception {
        String main = id(send(admin, post("/api/v1/admin/areas"), area("Main campus", 120)));
        Caller qrManager = TestJwt.webUser("qr_manager", school);
        send(qrManager, post("/api/v1/admin/areas"), area("Field", 60)).andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/admin/areas/" + main).header("Authorization", qrManager.bearer())).andExpect(status().isForbidden());   // no delete
        mvc.perform(get("/api/v1/admin/areas").header("Authorization", TestJwt.webUser("viewer", school).bearer())).andExpect(status().isOk());
        send(TestJwt.webUser("viewer", school), post("/api/v1/admin/areas"), area("No", 60)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/areas").header("Authorization", TestJwt.webUser("hr", school).bearer())).andExpect(status().isForbidden());
        // another school sees nothing of it
        UUID other = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Other school', now(), now())")
                .param("id", other).param("c", "O" + other.toString().substring(0, 8)).update();
        mvc.perform(get("/api/v1/admin/areas/" + main).header("Authorization", TestJwt.webUser("super_admin", other).bearer()))
                .andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get("/api/v1/admin/areas").header("Authorization", TestJwt.webUser("super_admin", other).bearer())))).isEmpty();
        mvc.perform(get("/api/v1/admin/areas").header("Authorization", TestJwt.appUser("staff").bearer())).andExpect(status().isForbidden());
    }

    @Test
    void whereAPointIs() throws Exception {
        assertThat(SchoolOverride.callAs(school, () -> locations.locate(LAT, LNG))).isEmpty();   // no area yet
        String main = id(send(admin, post("/api/v1/admin/areas"), area("Main campus", 120)));
        send(admin, post("/api/v1/admin/areas"), "{\"name\":\"Sports field\",\"latitude\":11.5580,\"longitude\":104.9300,\"radiusMeters\":60}")
                .andExpect(status().isCreated());

        // 100 m north of the centre: inside the main campus
        double north100 = LAT + 100 / 111_195.0;
        Locations.AreaHit in = SchoolOverride.callAs(school, () -> locations.locate(north100, LNG)).orElseThrow();
        assertThat(in.areaId().toString()).isEqualTo(main);
        assertThat(in.inside()).isTrue();
        assertThat(in.distanceMeters()).isCloseTo(100, within(1.0));
        assertThat(in.metersOutside()).isZero();

        // 1.2 km away: outside, measured to the nearest edge (the design's "1.2 km away")
        double north1200 = LAT + 1200 / 111_195.0;
        Locations.AreaHit out = SchoolOverride.callAs(school, () -> locations.locate(north1200, LNG)).orElseThrow();
        assertThat(out.inside()).isFalse();
        assertThat(out.name()).isEqualTo("Sports field");   // its edge is nearer than the campus edge
        assertThat(out.metersOutside()).isBetween(900.0, 1100.0);

        // the haversine itself: Phnom Penh – Siem Reap airports ≈ 235 km; a degree of latitude ≈ 111.2 km
        assertThat(SchoolArea.distance(11.5466, 104.8441, 13.4107, 103.8128)).isCloseTo(235_000, within(3_000.0));
        assertThat(SchoolArea.distance(0, 0, 1, 0)).isCloseTo(111_195, within(1.0));
        // gates for the scan: inactive ones still known
        String gate = id(send(admin, post("/api/v1/admin/gates"), "{\"name\":\"Main gate\",\"areaId\":\"" + main + "\"}"));
        mvc.perform(delete("/api/v1/admin/gates/" + gate).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        assertThat(SchoolOverride.callAs(school, () -> locations.gate(UUID.fromString(gate)))).hasValueSatisfying(g -> {
            assertThat(g.name()).isEqualTo("Main gate");
            assertThat(g.active()).isFalse();
        });
        assertThat(SchoolOverride.callAs(UUID.randomUUID(), () -> locations.gate(UUID.fromString(gate)))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static String area(String name, int radius) {
        return "{\"name\":\"" + name + "\",\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"radiusMeters\":" + radius + "}";
    }

    private ResultActions send(Caller caller, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
