package kh.cmt.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Step 4.4 "done when": the pay history shows old → new with effective dates. */
@ApiTest
class PayTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private UUID school;
    private Caller admin;
    private String person;
    private LocalDate today;

    @BeforeEach
    void schoolWithAPerson() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'Pay school', now(), now())")
                .param("id", school).param("c", "Y" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        person = body(mvc.perform(post("/api/v1/admin/people").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"km\":\"សុខា\",\"en\":\"Sokha Lim\"},\"role\":\"staff\"}")).andExpect(status().isCreated()))
                .get("id").asText();
        today = LocalDate.now(clock);
    }

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void thePayHistoryShowsOldToNewWithEffectiveDates() throws Exception {
        setPay(monthly("420", today.minusYears(1)).with("bankName", "ABA Bank").with("accountNumber", "000 123 456")
                .with("allowances", List.of(Map.of("name", "Transport", "amount", 30)))).andExpect(status().isCreated());
        JsonNode history = body(setPay(monthly("450", today.minusMonths(1)).with("bankName", "ABA Bank").with("accountNumber", "000 123 456")
                .with("allowances", List.of(Map.of("name", "Transport", "amount", 30)))).andExpect(status().isCreated()));

        assertThat(history.get("history")).hasSize(2);
        JsonNode newest = history.get("history").get(0);
        assertThat(newest.get("status").asText()).isEqualTo("CURRENT");
        assertThat(newest.get("effectiveFrom").asText()).isEqualTo(today.minusMonths(1).toString());
        assertThat(newest.get("changes")).hasSize(1);   // only the amount changed
        assertThat(newest.get("changes").get(0).get("field").asText()).isEqualTo("amount");
        assertThat(newest.get("changes").get(0).get("before").asText()).isEqualTo("$420");
        assertThat(newest.get("changes").get(0).get("after").asText()).isEqualTo("$450");
        assertThat(newest.get("changedBy").asText()).isEqualTo(admin.userId().toString());
        JsonNode first = history.get("history").get(1);
        assertThat(first.get("status").asText()).isEqualTo("PAST");
        assertThat(first.get("effectiveFrom").asText()).isEqualTo(today.minusYears(1).toString());
        assertThat(first.get("changes")).isEmpty();
        assertThat(history.get("current").get("amount").decimalValue()).isEqualByComparingTo("450");
        assertThat(history.get("current").get("paymentDay").asInt()).isEqualTo(28);   // the design's default

        // a planned change shows as upcoming, the current pay stays
        JsonNode planned = body(setPay(new Form("hourly", "6", today.plusDays(10)).with("overtime", "2")));
        assertThat(planned.get("upcoming")).hasSize(1);
        assertThat(planned.get("upcoming").get(0).get("changes").toString()).contains("\"field\":\"type\"").contains("\"before\":\"monthly\"")
                .contains("\"after\":\"hourly\"");
        assertThat(planned.get("current").get("type").asText()).isEqualTo("monthly");
        assertThat(body(mvc.perform(get("/api/v1/admin/people/" + person + "/pay").header("Authorization", admin.bearer())))
                .get("history")).hasSize(3);
    }

    @Test
    void rowsAreNeverChanged() throws Exception {
        setPay(monthly("420", today.minusMonths(2))).andExpect(status().isCreated());
        // not before the newest row, and not on the date of a row already in force
        setPay(monthly("400", today.minusMonths(3))).andExpect(jsonPath("$.errors[0].rule").value("BeforeLatest"));
        setPay(monthly("430", today.minusMonths(2))).andExpect(jsonPath("$.errors[0].rule").value("SameDate"));
        // a planned change that has not started yet may still be corrected (same date replaces it)
        setPay(monthly("440", today.plusDays(5))).andExpect(status().isCreated());
        JsonNode corrected = body(setPay(monthly("445", today.plusDays(5))).andExpect(status().isCreated()));
        assertThat(corrected.get("history")).hasSize(2);
        assertThat(corrected.get("upcoming").get(0).get("amount").decimalValue()).isEqualByComparingTo("445");

        // and the database itself refuses any change of a row
        assertThatThrownBy(() -> jdbc.sql("update pay_rate set amount = 1 where person_id = :p").param("p", UUID.fromString(person)).update())
                .hasMessageContaining("never changed");
    }

    @Test
    void eachPayTypeHasItsOwnFields() throws Exception {
        expect(setPay(new Form(null, null, null)), "type:NotNull", "effectiveFrom:NotNull");
        expect(setPay(new Form("weekly", "5", today)), "type:OneOf");
        expect(setPay(new Form("monthly", null, today)), "amount:NotNull");
        expect(setPay(new Form("none", "100", today)), "amount:NotForType");
        expect(setPay(monthly("-5", today)), "amount:Range");
        expect(setPay(monthly("450.555", today)), "amount:Scale");
        expect(setPay(monthly("1800000.5", today).with("currency", "KHR")), "amount:WholeRiel");
        expect(setPay(monthly("450", today).with("currency", "EUR")), "currency:OneOf");
        expect(setPay(monthly("450", today).with("paymentDay", 32)), "paymentDay:Range");
        expect(setPay(monthly("450", today).with("overtime", "2")), "overtime:NotForType");
        expect(setPay(new Form("teaching", "7.5", today)), "type:OneOf");   // no pay per teaching hour
        expect(setPay(new Form("hourly", "5", today).with("overtime", "custom")), "overtimeRate:NotNull");
        expect(setPay(new Form("hourly", "5", today).with("overtime", "3")), "overtime:OneOf");
        expect(setPay(new Form("hourly", "5", today).with("allowances", List.of(Map.of("name", "Transport", "amount", 30)))),
                "allowances:NotForType");
        expect(setPay(monthly("450", today).with("allowances", List.of(Map.of("name", "", "amount", 30)))), "allowances.name:NotBlank");
        expect(setPay(monthly("450", today).with("accountNumber", "#!")), "accountNumber:Pattern");

        // the good ones
        JsonNode hourly = body(setPay(new Form("hourly", "5", today.minusDays(2)).with("overtime", "custom").with("overtimeRate", 9)));
        assertThat(hourly.get("current").get("overtimeRate").decimalValue()).isEqualByComparingTo("9");
        assertThat(hourly.get("current").get("currency").asText()).isEqualTo("USD");
        JsonNode riel = body(setPay(monthly("1800000", today.minusDays(1)).with("currency", "KHR")));
        assertThat(riel.get("history").get(0).get("changes").toString()).contains("៛1,800,000");
        JsonNode none = body(setPay(new Form("none", null, today)).andExpect(status().isCreated()));
        assertThat(none.get("current").has("amount")).isFalse();
    }

    // ------------------------------------------------------------------ who may see pay

    @Test
    void payNeedsTheSalaryFlag() throws Exception {
        setPay(monthly("450", today.minusDays(1))).andExpect(status().isCreated());

        // HR has "See salary & bank details": history, profile and list show the pay
        Caller hr = TestJwt.webUser("hr", school);
        mvc.perform(get("/api/v1/admin/people/" + person + "/pay").header("Authorization", hr.bearer())).andExpect(status().isOk());
        JsonNode profile = body(mvc.perform(get("/api/v1/admin/people/" + person).header("Authorization", hr.bearer())));
        assertThat(profile.get("pay").get("type").asText()).isEqualTo("monthly");
        assertThat(profile.get("pay").get("amount").decimalValue()).isEqualByComparingTo("450");
        assertThat(body(mvc.perform(get("/api/v1/admin/people").header("Authorization", hr.bearer())))
                .get("items").get(0).get("payType").asText()).isEqualTo("monthly");

        // Viewer and QR Manager do not: no history, "••••" on the profile and the list
        for (String role : List.of("viewer", "qr_manager")) {
            Caller without = TestJwt.webUser(role, school);
            mvc.perform(get("/api/v1/admin/people/" + person + "/pay").header("Authorization", without.bearer()))
                    .andExpect(status().isForbidden());
        }
        Caller viewer = TestJwt.webUser("viewer", school);
        assertThat(body(mvc.perform(get("/api/v1/admin/people/" + person).header("Authorization", viewer.bearer())))
                .get("pay").asText()).isEqualTo("••••");
        assertThat(body(mvc.perform(get("/api/v1/admin/people").header("Authorization", viewer.bearer())))
                .get("items").get(0).get("payType").asText()).isEqualTo("••••");

        // a role with People edit but without the flag cannot set pay either
        JsonNode clerk = body(mvc.perform(post("/api/v1/admin/roles").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":{\"en\":\"People clerk\"}}")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/roles/" + clerk.get("id").asText() + "/permissions")
                .header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":{\"people\":[\"view\",\"edit\"]},\"version\":" + clerk.get("version") + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/people/" + person + "/pay").header("Authorization", TestJwt.webUser(clerk.get("code").asText(), school).bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(monthly("999", today.plusDays(1)).fields)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.field").value("salaryBank"));

        // another school: not found
        mvc.perform(get("/api/v1/admin/people/" + person + "/pay").header("Authorization", TestJwt.webUser("super_admin").bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void payHistoryKeepsAPersonFromBeingDeletedForever() throws Exception {
        setPay(monthly("450", today)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/people/" + person + "/inactivate").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MISTAKE\"}")).andExpect(status().isNoContent());
        setPay(monthly("460", today.plusDays(1))).andExpect(jsonPath("$.code").value("CONFLICT"));   // not for inactive people
        mvc.perform(delete("/api/v1/admin/people/" + person).header("Authorization", admin.bearer())).andExpect(jsonPath("$.code").value("IN_USE"));
    }

    // ------------------------------------------------------------------ helpers

    /** A pay form; {@code with} adds fields. */
    private static final class Form {
        final Map<String, Object> fields = new HashMap<>();

        Form(String type, String amount, LocalDate from) {
            if (type != null) {
                fields.put("type", type);
            }
            if (amount != null) {
                fields.put("amount", new java.math.BigDecimal(amount));
            }
            if (from != null) {
                fields.put("effectiveFrom", from.toString());
            }
        }

        Form with(String field, Object value) {
            fields.put(field, value);
            return this;
        }
    }

    private static Form monthly(String amount, LocalDate from) {
        return new Form("monthly", amount, from);
    }

    private ResultActions setPay(Form form) throws Exception {
        return mvc.perform(post("/api/v1/admin/people/" + person + "/pay").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(form.fields)));
    }

    private void expect(ResultActions result, String... fieldRules) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> found = new java.util.ArrayList<>();
        problem.get("errors").forEach(e -> found.add(e.get("field").asText() + ":" + e.get("rule").asText()));
        assertThat(found).contains(fieldRules);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
