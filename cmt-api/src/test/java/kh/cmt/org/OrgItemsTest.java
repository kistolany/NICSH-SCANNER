package kh.cmt.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.support.TestRoles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Step 2.1: the org lists (departments, positions) end to end. */
@ApiTest
class OrgItemsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrgQueries orgQueries;

    Caller hr;   // an office user who may manage the People lists, but is not a Super Admin
    final Caller superAdmin = TestJwt.webUser("super_admin");

    @BeforeEach
    void officeUser() throws Exception {
        List<String> all = List.of("view", "create", "edit", "delete", "export");
        hr = TestRoles.callerWithRole(mvc, json, "Org editor", Map.of("people", all));
    }

    /** A name no other test uses (the database is shared by the tests of this class). */
    static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ who may call

    @Test
    void onlyOfficeUsersMayUseTheOrgEndpoints() throws Exception {
        mvc.perform(get("/api/v1/admin/departments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/departments").header("Authorization", TestJwt.appUser("admin").bearer()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ departments

    @Test
    void createReadChangeAndListADepartment() throws Exception {
        String en = unique("Primary school");

        JsonNode created = body(send(hr, post("/api/v1/admin/departments"),
                "{\"name\":{\"en\":\"" + en + "\",\"km\":\"បឋមសិក្សា\"},\"color\":\"#2563eb\"}").andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asText());

        assertThat(created.get("name").get("en").asText()).isEqualTo(en);
        assertThat(created.get("name").get("km").asText()).isEqualTo("បឋមសិក្សា");   // Khmer survives the round trip
        assertThat(created.get("color").asText()).isEqualTo("#2563EB");               // normalised
        assertThat(created.get("inactive").asBoolean()).isFalse();
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.has("label")).isFalse();

        // who and which school: from the token
        assertThat(jdbc.queryForObject("select created_by from department where id = ?", UUID.class, id))
                .isEqualTo(hr.userId());
        assertThat(jdbc.queryForObject("select school_id from department where id = ?", UUID.class, id))
                .isEqualTo(TestJwt.SCHOOL);

        // read it back and find it in the list
        mvc.perform(get("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name.en").value(en));
        assertThat(names(list(hr, "departments", "active", null))).contains(en);

        // change it, sending the version that was read
        String renamed = unique("Primary");
        JsonNode changed = body(send(hr, put("/api/v1/admin/departments/" + id),
                "{\"name\":{\"en\":\"" + renamed + "\"},\"color\":\"#9333EA\",\"version\":0}").andExpect(status().isOk()));
        assertThat(changed.get("name").get("en").asText()).isEqualTo(renamed);
        assertThat(changed.get("name").has("km")).isFalse();     // not sent -> cleared
        assertThat(changed.get("color").asText()).isEqualTo("#9333EA");
        assertThat(changed.get("version").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select updated_by from department where id = ?", UUID.class, id))
                .isEqualTo(hr.userId());
    }

    @Test
    void aDepartmentWithoutAColorGetsTheDefaultOne() throws Exception {
        JsonNode created = body(send(hr, post("/api/v1/admin/departments"),
                "{\"name\":{\"en\":\"" + unique("Finance") + "\"}}").andExpect(status().isCreated()));
        assertThat(created.get("color").asText()).isEqualTo("#4B5563");
    }

    @Test
    void badInputIsRefusedWithTheFieldAndRule() throws Exception {
        send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"km\":\"ខ្មែរ\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name.en"))
                .andExpect(jsonPath("$.errors[0].rule").value("NotBlank"));
        send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"   \"}}").andExpect(status().isBadRequest());
        send(hr, post("/api/v1/admin/departments"), "{}").andExpect(status().isBadRequest());
        send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"X " + unique("a") + "\"},\"color\":\"blue\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("color"))
                .andExpect(jsonPath("$.errors[0].rule").value("Pattern"));
        send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + "x".repeat(101) + "\"}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNameMustBeUniqueAmongActiveItemsIgnoringCaseAndSpaces() throws Exception {
        String en = unique("Front office");
        send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + en + "\"}}").andExpect(status().isCreated());

        send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"  " + en.toUpperCase().replace(" ", "   ") + " \"}}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));

        // the same name in another list is fine
        send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + en + "\"}}").andExpect(status().isCreated());
    }

    @Test
    void changingAnItemSomebodyElseChangedIsReportedNotOverwritten() throws Exception {
        JsonNode created = body(send(hr, post("/api/v1/admin/departments"),
                "{\"name\":{\"en\":\"" + unique("IT") + "\"}}").andExpect(status().isCreated()));
        String id = created.get("id").asText();
        String en = created.get("name").get("en").asText();

        send(hr, put("/api/v1/admin/departments/" + id), "{\"name\":{\"en\":\"" + en + "\"},\"color\":\"#16A34A\",\"version\":0}")
                .andExpect(status().isOk());
        // a second person still has version 0 on screen
        send(hr, put("/api/v1/admin/departments/" + id), "{\"name\":{\"en\":\"" + en + "\"},\"color\":\"#EA580C\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // no version at all
        send(hr, put("/api/v1/admin/departments/" + id), "{\"name\":{\"en\":\"" + en + "\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));

        mvc.perform(get("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer()))
                .andExpect(jsonPath("$.color").value("#16A34A"));   // the first change stands
    }

    @Test
    void renamingToTheNameOfAnotherActiveItemIsRefusedButKeepingYourOwnNameIsFine() throws Exception {
        String a = unique("Alpha");
        String b = unique("Beta");
        JsonNode first = body(send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + a + "\"}}"));
        JsonNode second = body(send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + b + "\"}}"));

        send(hr, put("/api/v1/admin/departments/" + second.get("id").asText()),
                "{\"name\":{\"en\":\"" + a.toLowerCase() + "\"},\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        send(hr, put("/api/v1/admin/departments/" + first.get("id").asText()),
                "{\"name\":{\"en\":\"" + a + "\",\"km\":\"អាល់ហ្វា\"},\"version\":0}").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ inactive and restore

    @Test
    void movingToInactiveHidesItFromTheActiveListKeepsTheHistoryAndRestoreBringsItBack() throws Exception {
        String en = unique("Facilities");
        String id = body(send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + en + "\"}}")).get("id").asText();

        mvc.perform(delete("/api/v1/admin/departments/" + id).param("reason", "Merged into Administration")
                .header("Authorization", superAdmin.bearer())).andExpect(status().isNoContent());

        // gone from the active list (this is what dropdowns use)...
        assertThat(names(list(hr, "departments", "active", null))).doesNotContain(en);
        // ...present in the Inactive tab with who / when / why
        JsonNode inactive = find(list(hr, "departments", "inactive", null), id);
        assertThat(inactive.get("inactive").asBoolean()).isTrue();
        assertThat(inactive.get("deleteReason").asText()).isEqualTo("Merged into Administration");
        assertThat(inactive.get("deletedBy").asText()).isEqualTo(superAdmin.userId().toString());
        assertThat(inactive.get("deletedAt").asText()).isNotBlank();
        // old records can still open it
        mvc.perform(get("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inactive").value(true));
        // moving it again changes nothing
        mvc.perform(delete("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer()))
                .andExpect(status().isNoContent());
        assertThat(find(list(hr, "departments", "inactive", null), id).get("deleteReason").asText())
                .isEqualTo("Merged into Administration");

        // restore
        mvc.perform(post("/api/v1/admin/departments/" + id + "/restore").header("Authorization", hr.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inactive").value(false))
                .andExpect(jsonPath("$.deleteReason").doesNotExist());
        assertThat(names(list(hr, "departments", "active", null))).contains(en);
        assertThat(find(list(hr, "departments", "inactive", null), id)).isNull();
    }

    @Test
    void aRetiredNameCanBeUsedAgainAndRestoringTheOldOneThenNeedsAFreeName() throws Exception {
        String en = unique("Security");
        String oldId = body(send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + en + "\"}}")).get("id").asText();
        mvc.perform(delete("/api/v1/admin/positions/" + oldId).header("Authorization", hr.bearer()))
                .andExpect(status().isNoContent());

        String newId = body(send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + en + "\"}}")
                .andExpect(status().isCreated())).get("id").asText();   // allowed: the old one is inactive

        mvc.perform(post("/api/v1/admin/positions/" + oldId + "/restore").header("Authorization", hr.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));

        mvc.perform(delete("/api/v1/admin/positions/" + newId).header("Authorization", hr.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/admin/positions/" + oldId + "/restore").header("Authorization", hr.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void orgQueriesTellsOtherModulesWhetherAnItemMayBeChosen() throws Exception {
        String id = body(send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + unique("Clerk") + "\"}}")).get("id").asText();
        assertThat(orgQueries.isActive(OrgKind.POSITION, UUID.fromString(id))).isTrue();
        assertThat(orgQueries.isActive(OrgKind.DEPARTMENT, UUID.fromString(id))).isFalse();   // not a department
        assertThat(orgQueries.isActive(OrgKind.POSITION, UUID.randomUUID())).isFalse();

        mvc.perform(delete("/api/v1/admin/positions/" + id).header("Authorization", hr.bearer())).andExpect(status().isNoContent());
        assertThat(orgQueries.isActive(OrgKind.POSITION, UUID.fromString(id))).isFalse();
    }

    // ------------------------------------------------------------------ delete forever

    @Test
    void deleteForeverIsForSuperAdminOnlyAndOnlyForInactiveItems() throws Exception {
        String id = body(send(hr, post("/api/v1/admin/departments"),
                "{\"name\":{\"en\":\"" + unique("Library") + "\"},\"color\":\"#92400E\"}")).get("id").asText();

        // not inactive yet
        mvc.perform(delete("/api/v1/admin/departments/" + id + "/forever").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_INACTIVE"));

        mvc.perform(delete("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer())).andExpect(status().isNoContent());

        // HR may retire items but not delete them for good
        mvc.perform(delete("/api/v1/admin/departments/" + id + "/forever").header("Authorization", hr.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(delete("/api/v1/admin/departments/" + id + "/forever").header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anItemThatOldRecordsStillPointToCannotBeDeletedForever() throws Exception {
        String id = body(send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + unique("Used") + "\"}}"))
                .get("id").asText();
        jdbc.execute("create table if not exists org_fk_probe (dept uuid references department (id))");
        try {
            jdbc.update("insert into org_fk_probe values (?)", UUID.fromString(id));   // stands in for a person record
            mvc.perform(delete("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer())).andExpect(status().isNoContent());

            mvc.perform(delete("/api/v1/admin/departments/" + id + "/forever").header("Authorization", superAdmin.bearer()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));
            // still there, still inactive
            mvc.perform(get("/api/v1/admin/departments/" + id).header("Authorization", hr.bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.inactive").value(true));
        } finally {
            jdbc.execute("drop table org_fk_probe");
        }
    }

    // ------------------------------------------------------------------ the other kinds

    @Test
    void departmentsHaveANameAndAColor_positionsOnlyAName() throws Exception {
        JsonNode department = body(send(hr, post("/api/v1/admin/departments"),
                "{\"name\":{\"en\":\"" + unique("Finance") + "\",\"km\":\"ហិរញ្ញវត្ថុ\"},\"color\":\"#2563EB\"}"));
        assertThat(department.get("color").asText()).isEqualTo("#2563EB");

        JsonNode position = body(send(hr, post("/api/v1/admin/positions"),
                "{\"name\":{\"en\":\"" + unique("Accountant") + "\"},\"color\":\"#2563EB\"}"));
        assertThat(position.has("color")).isFalse();   // positions have no colour, a sent one is ignored
    }

    // ------------------------------------------------------------------ searching, paging, tenants

    @Test
    void searchFindsEnglishAndKhmerNamesAndIsNotConfusedByWildcards() throws Exception {
        String token = "Zq" + UUID.randomUUID().toString().substring(0, 6);
        send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + token + " Officer\",\"km\":\"មន្ត្រី" + token + "\"}}");
        send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + token + "_100%\"}}");

        assertThat(names(list(hr, "positions", "active", token.toLowerCase()))).hasSize(2);
        assertThat(names(list(hr, "positions", "active", "មន្ត្រី" + token))).containsExactly(token + " Officer");
        assertThat(names(list(hr, "positions", "active", token + "_100%"))).containsExactly(token + "_100%");
        // a % is a character, not a wildcard: only the name that really contains one is found
        assertThat(names(list(hr, "positions", "active", "%"))).allMatch(n -> n.contains("%")).contains(token + "_100%");
        assertThat(names(list(hr, "positions", "active", token + "x%"))).isEmpty();
    }

    @Test
    void listsArePagedAndSortedByName() throws Exception {
        String prefix = "Pg" + UUID.randomUUID().toString().substring(0, 6);
        for (String suffix : new String[] {"c", "a", "b"}) {
            send(hr, post("/api/v1/admin/positions"), "{\"name\":{\"en\":\"" + prefix + suffix + "\"}}");
        }
        MvcResult page1 = mvc.perform(get("/api/v1/admin/positions").param("q", prefix).param("size", "2").param("page", "0")
                .header("Authorization", hr.bearer())).andExpect(status().isOk()).andReturn();
        JsonNode first = json.readTree(page1.getResponse().getContentAsString());
        assertThat(first.get("total").asLong()).isEqualTo(3);
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("items")).extracting(n -> n.get("name").get("en").asText()).containsExactly(prefix + "a", prefix + "b");

        JsonNode second = json.readTree(mvc.perform(get("/api/v1/admin/positions").param("q", prefix).param("size", "2")
                .param("page", "1").header("Authorization", hr.bearer())).andReturn().getResponse().getContentAsString());
        assertThat(second.get("items")).extracting(n -> n.get("name").get("en").asText()).containsExactly(prefix + "c");
    }

    @Test
    void oddRequestsGetClearAnswers() throws Exception {
        mvc.perform(get("/api/v1/admin/departments").param("status", "deleted").header("Authorization", hr.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(get("/api/v1/admin/departments").param("size", "100000").header("Authorization", hr.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/widgets").header("Authorization", hr.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/admin/departments/" + UUID.randomUUID()).header("Authorization", hr.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/departments/not-a-uuid").header("Authorization", hr.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSchoolNeverSeesAnotherSchoolsItems() throws Exception {
        UUID otherSchool = UUID.randomUUID();
        UUID foreignDept = UUID.randomUUID();
        String name = unique("Foreign");
        jdbc.update("insert into school (id, code, name_en, created_at, updated_at) values (?, ?, 'Other School', now(), now())",
                otherSchool, "T" + otherSchool.toString().substring(0, 8));
        jdbc.update("""
                insert into department (id, school_id, name_en, created_at, updated_at) values (?, ?, ?, now(), now())
                """, foreignDept, otherSchool, name);
        try {
            assertThat(names(list(hr, "departments", "active", name))).isEmpty();
            mvc.perform(get("/api/v1/admin/departments/" + foreignDept).header("Authorization", hr.bearer()))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/admin/departments/" + foreignDept).header("Authorization", hr.bearer()))
                    .andExpect(status().isNotFound());
            assertThat(orgQueries.isActive(OrgKind.DEPARTMENT, foreignDept)).isFalse();
            // and the other school's name does not block ours
            send(hr, post("/api/v1/admin/departments"), "{\"name\":{\"en\":\"" + name + "\"}}").andExpect(status().isCreated());
        } finally {
            jdbc.update("delete from department where school_id = ?", otherSchool);
            jdbc.update("delete from school where id = ?", otherSchool);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions send(Caller caller, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String content) throws Exception {
        return mvc.perform(request.header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode list(Caller caller, String kind, String status, String q) throws Exception {
        var request = get("/api/v1/admin/" + kind).param("status", status).param("size", "500")
                .header("Authorization", caller.bearer());
        if (q != null) {
            request = request.param("q", q);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("items");
    }

    private static java.util.List<String> names(JsonNode items) {
        java.util.List<String> out = new java.util.ArrayList<>();
        items.forEach(i -> out.add(i.has("name") ? i.get("name").get("en").asText() : i.get("label").asText()));
        return out;
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (item.get("id").asText().equals(id)) {
                return item;
            }
        }
        return null;
    }
}
