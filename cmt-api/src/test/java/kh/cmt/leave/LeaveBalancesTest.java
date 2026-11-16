package kh.cmt.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestClock;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 9.1: leave types (unique names, a code made once, inactive and back) and balances (the type's days, pro-rata in
 * the start year, adjustments with a reason, never below what was used), the app's balances and Home. Today is
 * Monday 26 Oct 2026.
 */
@ApiTest
class LeaveBalancesTest {

    static final ZoneId PP = ZoneId.of("Asia/Phnom_Penh");
    static final String TYPES = "/api/v1/admin/leave-types";
    static final String BAL = "/api/v1/admin/leave-balances";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TestClock clock;

    private UUID school;
    private Caller admin;

    @BeforeEach
    void school() {
        clock.set(LocalDate.of(2026, 10, 26).atTime(9, 0).atZone(PP).toInstant());
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Leave school', now(), now())")
                .param("id", school).param("c", "L" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    @Test
    void leaveTypes_areUniqueByName_getACodeOnce_andGoInactiveAndBack() throws Exception {
        assertThat(body(mvc.perform(get(TYPES).header("Authorization", admin.bearer())).andExpect(status().isOk()))).isEmpty();
        JsonNode annual = body(send(post(TYPES), type("Annual leave", "12", "true", "NONE", "#1d4ed8")).andExpect(status().isCreated()));
        assertThat(List.of(annual.get("code").asText(), annual.get("daysPerYear").asText(), annual.get("color").asText(),
                annual.get("attachment").asText())).containsExactly("annual", "12.0", "#1D4ED8", "NONE");
        JsonNode sick = body(send(post(TYPES), "{\"name\":{\"en\":\"Sick leave\",\"km\":\"ច្បាប់ឈឺ\"},\"daysPerYear\":10,\"attachment\":\"over_one_day\","
                + "\"color\":\"#B91C1C\"}").andExpect(status().isCreated()));
        assertThat(sick.get("halfDays").asBoolean()).isTrue();   // by default
        assertThat(sick.get("attachment").asText()).isEqualTo("OVER_ONE_DAY");
        assertThat(code(send(post(TYPES), type("annual LEAVE", "5", "true", "NONE", "#1D4ED8")).andExpect(status().isConflict()))).isEqualTo("NAME_TAKEN");
        assertThat(rules(send(post(TYPES), "{\"name\":{\"en\":\" \",\"km\":\"" + "ក".repeat(101) + "\"},\"daysPerYear\":12.3,\"attachment\":\"SOMETIMES\","
                + "\"color\":\"blue\"}"))).containsExactlyInAnyOrder("name.en NotBlank", "name.km Size", "daysPerYear Step", "attachment OneOf", "color Pattern");
        assertThat(rules(send(post(TYPES), "{\"name\":{\"en\":\"X\"},\"daysPerYear\":400,\"color\":\"#000000\"}"))).containsExactly("daysPerYear Range");

        // change: the version must be the one seen
        String id = annual.get("id").asText();
        JsonNode changed = body(send(put(TYPES + "/" + id), withVersion(type("Annual leave", "14.5", "true", "NONE", "#1D4ED8"), 0))
                .andExpect(status().isOk()));
        assertThat(changed.get("daysPerYear").asText()).isEqualTo("14.5");
        assertThat(changed.get("code").asText()).isEqualTo("annual");
        assertThat(code(send(put(TYPES + "/" + id), withVersion(type("Annual leave", "12", "true", "NONE", "#1D4ED8"), 0))
                .andExpect(status().isConflict()))).isEqualTo("VERSION_CONFLICT");

        // inactive: kept with the reason; the name is free again (a new code); back only while the name is free
        mvc.perform(delete(TYPES + "/" + id + "?reason=Replaced").header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        assertThat(names(body(mvc.perform(get(TYPES).header("Authorization", admin.bearer()))))).containsExactly("Sick leave");
        JsonNode inactive = body(mvc.perform(get(TYPES + "?status=inactive").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(inactive.get(0).get("deleteReason").asText()).isEqualTo("Replaced");
        JsonNode again = body(send(post(TYPES), type("Annual leave", "12", "true", "NONE", "#1D4ED8")).andExpect(status().isCreated()));
        assertThat(again.get("code").asText()).isEqualTo("annual_2");
        assertThat(code(mvc.perform(post(TYPES + "/" + id + "/restore").header("Authorization", admin.bearer())).andExpect(status().isConflict())))
                .isEqualTo("NAME_TAKEN");
        mvc.perform(delete(TYPES + "/" + again.get("id").asText()).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        assertThat(body(mvc.perform(post(TYPES + "/" + id + "/restore").header("Authorization", admin.bearer())).andExpect(status().isOk()))
                .get("inactive").asBoolean()).isFalse();
        assertThat(code(mvc.perform(post(TYPES + "/" + id + "/restore").header("Authorization", admin.bearer())).andExpect(status().isConflict())))
                .isEqualTo("NOT_INACTIVE");
        mvc.perform(get(TYPES + "?status=all").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());
        mvc.perform(get(TYPES).header("Authorization", TestJwt.webUser("viewer", school).bearer())).andExpect(status().isOk());
    }

    @Test
    void balances_theTypesDays_proRataInTheStartYear_plusAdjustments() throws Exception {
        String annual = id(send(post(TYPES), type("Annual leave", "12", "true", "NONE", "#1D4ED8")));
        String sick = id(send(post(TYPES), type("Sick leave", "10", "true", "OVER_ONE_DAY", "#B91C1C")));
        String maternity = id(send(post(TYPES), type("Maternity leave", "90", "false", "ALWAYS", "#15803D")));
        String old = id(send(post(TYPES), type("Old leave", "5", "true", "NONE", "#000000")));
        mvc.perform(delete(TYPES + "/" + old).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        String dara = person("Dara Chan", null);
        String sokha = person("Sokha Lim", "2026-08-10");     // started this year: 5 months
        String vanna = person("Vanna Kim", "2027-01-05");     // starts next year
        String navy = person("Navy Chhun", "2024-03-01");

        JsonNode table = body(mvc.perform(get(BAL).header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(table.get("year").asInt()).isEqualTo(2026);
        assertThat(names(table.get("types"))).containsExactly("Annual leave", "Sick leave", "Maternity leave");   // active types only
        assertThat(row(table, dara)).containsExactly("12.0", "10.0", "90.0");
        assertThat(row(table, sokha)).containsExactly("5.0", "4.0", "37.0");    // 12 × 5/12; 4.17 → 4; 37.5 → 37 (whole days)
        assertThat(row(table, vanna)).containsExactly("0.0", "0.0", "0.0");
        assertThat(row(table, navy)).containsExactly("12.0", "10.0", "90.0");
        assertThat(cell(table, sokha, 0).get("entitled").asText()).isEqualTo("5.0");
        assertThat(rowOf(table, sokha).get("person").get("startDate").asText()).isEqualTo("2026-08-10");
        JsonNode next = body(mvc.perform(get(BAL + "?year=2027").header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(row(next, sokha)).containsExactly("12.0", "10.0", "90.0");   // full days from the next year
        assertThat(row(next, vanna)).containsExactly("12.0", "10.0", "90.0");   // starts in January: 12 months
        mvc.perform(get(BAL + "?year=1999").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());

        // adjust: + carried over, − taken away; never below what was used (nothing yet), half days only where allowed
        JsonNode plus = body(send(post(BAL + "/" + dara + "/adjust"), adjust(annual, null, "2", "Carried over from 2025")).andExpect(status().isCreated()));
        JsonNode darAnnual = plus.get("row").get("balances").get(0);
        assertThat(List.of(darAnnual.get("entitled").asText(), darAnnual.get("adjusted").asText(), darAnnual.get("total").asText(),
                darAnnual.get("used").asText(), darAnnual.get("left").asText())).containsExactly("12.0", "2.0", "14.0", "0.0", "14.0");
        clock.set(LocalDate.of(2026, 10, 26).atTime(9, 5).atZone(PP).toInstant());
        send(post(BAL + "/" + dara + "/adjust"), adjust(annual, 2026, "-1.5", "Counted twice")).andExpect(status().isCreated());
        assertThat(rules(send(post(BAL + "/" + dara + "/adjust"), adjust(annual, null, "-13", "Too much")))).containsExactly("days BelowUsed");
        assertThat(rules(send(post(BAL + "/" + dara + "/adjust"), adjust(maternity, 2024, "0.5", " ")))).containsExactlyInAnyOrder(
                "year Range", "days Step", "reason NotBlank");
        assertThat(rules(send(post(BAL + "/" + dara + "/adjust"), adjust(old, null, "0", "x".repeat(301))))).containsExactlyInAnyOrder(
                "typeId Unknown", "days Range", "reason Size");   // an inactive type is not offered
        assertThat(rules(send(post(BAL + "/" + dara + "/adjust"), "{}"))).containsExactlyInAnyOrder("typeId NotNull", "days NotNull", "reason NotBlank");
        send(post(BAL + "/" + sokha + "/adjust"), adjust(sick, 2027, "3", "Agreed in the contract")).andExpect(status().isCreated());
        send(post(BAL + "/" + UUID.randomUUID() + "/adjust"), adjust(annual, null, "1", "Nobody")).andExpect(status().isNotFound());

        JsonNode detail = body(mvc.perform(get(BAL + "/" + dara).header("Authorization", admin.bearer())).andExpect(status().isOk()));
        assertThat(detail.get("row").get("balances").get(0).get("total").asText()).isEqualTo("12.5");
        assertThat(detail.get("adjustments")).extracting(a -> a.get("days").asText() + " " + a.get("reason").asText())
                .containsExactly("-1.5 Counted twice", "2.0 Carried over from 2025");   // newest first
        assertThat(row(body(mvc.perform(get(BAL).header("Authorization", admin.bearer()))), dara)).containsExactly("12.5", "10.0", "90.0");
        assertThat(row(body(mvc.perform(get(BAL + "?year=2027").header("Authorization", admin.bearer()))), sokha)).containsExactly("12.0", "13.0", "90.0");
        assertThat(body(mvc.perform(get(BAL + "/" + sokha).header("Authorization", admin.bearer()))).get("adjustments")).isEmpty();   // 2027's are 2027's
        mvc.perform(get(BAL + "/" + UUID.randomUUID()).header("Authorization", admin.bearer())).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select count(*) from leave_adjustment where person_id = :p").param("p", UUID.fromString(dara)).query(Long.class).single())
                .isEqualTo(2);

        // the person's own: the app's Leave page and Home
        Caller app = TestJwt.withPerson("app", "staff", school, UUID.randomUUID(), UUID.fromString(dara));
        JsonNode mine = body(mvc.perform(get("/api/v1/app/leave/balances").header("Authorization", app.bearer())).andExpect(status().isOk()));
        assertThat(mine.get("year").asInt()).isEqualTo(2026);
        assertThat(mine.get("balances")).extracting(b -> b.get("type").asText() + " " + b.get("total").asText() + " " + b.get("used").asText() + " "
                + b.get("left").asText()).containsExactly("annual 12.5 0.0 12.5", "sick 10.0 0.0 10.0", "maternity 90.0 0.0 90.0");
        JsonNode first = mine.get("balances").get(0);
        assertThat(List.of(first.get("typeId").asText(), first.get("name").get("en").asText(), first.get("color").asText(),
                first.get("halfDays").asText())).containsExactly(annual, "Annual leave", "#1D4ED8", "true");
        JsonNode home = body(mvc.perform(get("/api/v1/app/home").header("Authorization", app.bearer())).andExpect(status().isOk()));
        assertThat(home.get("leaveBalances")).extracting(b -> b.get("type").asText() + " " + b.get("left").asText())
                .containsExactly("annual 12.5", "sick 10.0", "maternity 90.0");
        mvc.perform(get("/api/v1/app/leave/balances").header("Authorization", TestJwt.appUser("staff").bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/leave/balances").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
        mvc.perform(get(BAL).header("Authorization", app.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void theCmtSchool_startsWithTheDesignsTypes() throws Exception {
        JsonNode seeded = body(mvc.perform(get(TYPES).header("Authorization", TestJwt.webUser("super_admin").bearer())).andExpect(status().isOk()));
        assertThat(seeded).extracting(t -> t.get("code").asText() + " " + t.get("daysPerYear").asText() + " " + t.get("halfDays").asText() + " "
                + t.get("attachment").asText()).startsWith("annual 12.0 true NONE", "sick 10.0 true OVER_ONE_DAY", "personal 3.0 true NONE",
                        "maternity 90.0 false ALWAYS");
        assertThat(seeded.get(0).get("name").get("km").asText()).isEqualTo("ច្បាប់ប្រចាំឆ្នាំ");
    }

    // ------------------------------------------------------------------ helpers

    private static String type(String en, String days, String halfDays, String attachment, String color) {
        return "{\"name\":{\"en\":\"" + en + "\"},\"daysPerYear\":" + days + ",\"halfDays\":" + halfDays + ",\"attachment\":\"" + attachment
                + "\",\"color\":\"" + color + "\"}";
    }

    private static String withVersion(String body, long version) {
        return body.substring(0, body.length() - 1) + ",\"version\":" + version + "}";
    }

    private static String adjust(String typeId, Integer year, String days, String reason) {
        return "{\"typeId\":\"" + typeId + "\"," + (year == null ? "" : "\"year\":" + year + ",") + "\"days\":" + days + ",\"reason\":\"" + reason + "\"}";
    }

    private String person(String en, String startDate) throws Exception {
        return id(send(post("/api/v1/admin/people"), "{\"name\":{\"km\":\"ក\",\"en\":\"" + en + "\"},\"role\":\"staff\""
                + (startDate == null ? "" : ",\"startDate\":\"" + startDate + "\"") + "}"));
    }

    private static JsonNode rowOf(JsonNode table, String personId) {
        for (JsonNode r : table.get("rows")) {
            if (r.get("person").get("id").asText().equals(personId)) {
                return r;
            }
        }
        throw new AssertionError("no row for " + personId);
    }

    private static JsonNode cell(JsonNode table, String personId, int i) {
        return rowOf(table, personId).get("balances").get(i);
    }

    /** "left" of each type in the person's row. */
    private static List<String> row(JsonNode table, String personId) {
        List<String> out = new ArrayList<>();
        rowOf(table, personId).get("balances").forEach(b -> out.add(b.get("left").asText()));
        return out;
    }

    private static List<String> names(JsonNode types) {
        List<String> out = new ArrayList<>();
        types.forEach(t -> out.add(t.get("name").get("en").asText()));
        return out;
    }

    private List<String> rules(ResultActions result) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> out = new ArrayList<>();
        problem.get("errors").forEach(e -> out.add(e.get("field").asText() + " " + e.get("rule").asText()));
        return out;
    }

    private String code(ResultActions result) throws Exception {
        return body(result).get("code").asText();
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
