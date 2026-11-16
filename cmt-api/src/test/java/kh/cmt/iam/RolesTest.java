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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 3.1: roles and the permission matrix. "Done when": the permission table saves, and removing View
 * clears the whole row (a row cannot keep Create / Edit / ... without View).
 */
@ApiTest
class RolesTest {

    /**
     * Which actions apply to each module, written out here again from the design (the rows of the permission
     * table) so that a mistake in the code cannot hide behind itself.
     */
    static final Map<String, List<String>> APPLICABLE = new LinkedHashMap<>();

    static {
        APPLICABLE.put("dashboard", List.of("view", "export"));
        APPLICABLE.put("approvals", List.of("view", "edit", "approve", "export"));
        APPLICABLE.put("attendance", List.of("view", "edit", "export"));
        APPLICABLE.put("people", List.of("view", "create", "edit", "delete", "export"));
        APPLICABLE.put("shifts", List.of("view", "create", "edit", "delete", "export"));
        APPLICABLE.put("leave", List.of("view", "create", "edit", "delete", "approve", "export"));
        APPLICABLE.put("qr", List.of("view", "create", "edit", "delete"));
        APPLICABLE.put("announcements", List.of("view", "create", "edit", "delete", "export"));
        APPLICABLE.put("reports", List.of("view", "export"));
        APPLICABLE.put("users", List.of("view", "create", "edit", "delete"));
        APPLICABLE.put("settings", List.of("view", "edit"));
        APPLICABLE.put("log", List.of("view", "export"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    final Caller superAdmin = TestJwt.webUser("super_admin");

    static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ the four system roles

    @Test
    void everySchoolStartsWithTheFourSystemRolesFromTheDesign() throws Exception {
        List<JsonNode> system = systemRoles(list(superAdmin));

        assertThat(system).extracting(r -> r.get("code").asText())
                .containsExactly("super_admin", "hr", "qr_manager", "viewer");
        assertThat(system).allSatisfy(r -> {
            assertThat(r.get("system").asBoolean()).isTrue();
            assertThat(r.get("permVersion").asInt()).isEqualTo(1);
            assertThat(r.get("version").asLong()).isZero();
        });
        assertThat(system).extracting(r -> r.get("name").get("en").asText())
                .containsExactly("Super Admin", "HR", "QR Manager", "Viewer");
        assertThat(system.get(1).get("name").get("km").asText()).isEqualTo("ធនធានមនុស្ស");
        assertThat(system.get(0).get("description").get("en").asText()).isEqualTo("Everything");

        // Super Admin: everything that applies, nothing that does not
        assertThat(matrix(system.get(0))).isEqualTo(APPLICABLE);

        // HR: people, attendance, leave, reports and the modules around them - with everything that applies
        Map<String, List<String>> hr = new LinkedHashMap<>();
        for (String m : List.of("dashboard", "approvals", "attendance", "people", "shifts", "leave", "announcements", "reports")) {
            hr.put(m, APPLICABLE.get(m));
        }
        assertThat(matrix(system.get(1))).isEqualTo(hr);

        // QR Manager: create and print QR only
        assertThat(matrix(system.get(2))).isEqualTo(Map.of("qr", List.of("view", "create", "edit")));

        // Viewer: read-only everywhere except users and settings
        Map<String, List<String>> viewer = new LinkedHashMap<>();
        APPLICABLE.keySet().stream().filter(m -> !m.equals("users") && !m.equals("settings")).forEach(m -> viewer.put(m, List.of("view")));
        assertThat(matrix(system.get(3))).isEqualTo(viewer);
    }

    @Test
    void theSystemRolesAreLocked() throws Exception {
        JsonNode hr = role("hr");
        String id = hr.get("id").asText();

        send(put("/api/v1/admin/roles/" + id + "/permissions"), "{\"permissions\":{\"people\":[\"view\"]},\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_LOCKED"));
        send(put("/api/v1/admin/roles/" + id), "{\"name\":{\"en\":\"Human resources\"},\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_LOCKED"));
        mvc.perform(delete("/api/v1/admin/roles/" + id).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_LOCKED"));

        assertThat(role("hr").get("permVersion").asInt()).isEqualTo(1);   // untouched
    }

    // ------------------------------------------------------------------ custom roles

    @Test
    void aCustomRoleStartsWithViewOnlyInTheChosenModules() throws Exception {
        String name = unique("Exam office");
        JsonNode created = body(send(post("/api/v1/admin/roles"),
                "{\"name\":{\"en\":\"" + name + "\",\"km\":\"ការិយាល័យប្រឡង\"},\"description\":{\"en\":\"Custom role\"},"
                        + "\"viewModules\":[\"dashboard\",\"leave\",\"shifts\",\"announcements\"]}").andExpect(status().isCreated()));

        assertThat(created.get("system").asBoolean()).isFalse();
        assertThat(created.get("code").asText()).startsWith("exam_office_");
        assertThat(created.get("name").get("km").asText()).isEqualTo("ការិយាល័យប្រឡង");
        assertThat(created.get("description").get("en").asText()).isEqualTo("Custom role");
        assertThat(created.get("permVersion").asInt()).isEqualTo(1);
        assertThat(matrix(created)).isEqualTo(Map.of("dashboard", List.of("view"), "shifts", List.of("view"),
                "leave", List.of("view"), "announcements", List.of("view")));
        assertThat(names(list(superAdmin))).contains(name);
    }

    @Test
    void roleNamesAreUniqueAndCodesAreNeverReused() throws Exception {
        String name = unique("Front desk");
        JsonNode first = body(send(post("/api/v1/admin/roles"), "{\"name\":{\"en\":\"" + name + "\"}}").andExpect(status().isCreated()));

        send(post("/api/v1/admin/roles"), "{\"name\":{\"en\":\"" + name.toUpperCase() + "\"}}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        // a different name that turns into the same code gets a numbered code
        JsonNode second = body(send(post("/api/v1/admin/roles"), "{\"name\":{\"en\":\"" + name + "!\"}}").andExpect(status().isCreated()));
        assertThat(second.get("code").asText()).isEqualTo(first.get("code").asText() + "_2");

        // after deleting the first, its name can be used again but its code stays retired
        mvc.perform(delete("/api/v1/admin/roles/" + first.get("id").asText()).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNoContent());
        JsonNode third = body(send(post("/api/v1/admin/roles"), "{\"name\":{\"en\":\"" + name + "\"}}").andExpect(status().isCreated()));
        assertThat(third.get("code").asText()).isEqualTo(first.get("code").asText() + "_3");
    }

    @Test
    void badNewRolesAreRefused() throws Exception {
        send(post("/api/v1/admin/roles"), "{\"name\":{\"km\":\"ខ្មែរ\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name.en"));
        send(post("/api/v1/admin/roles"), "{\"name\":{\"en\":\"  \"}}").andExpect(status().isBadRequest());
        send(post("/api/v1/admin/roles"), "{}").andExpect(status().isBadRequest());
        send(post("/api/v1/admin/roles"), "{\"name\":{\"en\":\"" + unique("X") + "\"},\"viewModules\":[\"dashboard\",\"payroll\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("viewModules"))
                .andExpect(jsonPath("$.errors[0].rule").value("UnknownModule"));
    }

    @Test
    void aCustomRoleCanBeRenamedButItsPermissionsStayAndPermVersionDoesNotMove() throws Exception {
        JsonNode created = createRole(unique("Temp"), "leave");
        String id = created.get("id").asText();
        String renamed = unique("Leave team");

        JsonNode updated = body(send(put("/api/v1/admin/roles/" + id),
                "{\"name\":{\"en\":\"" + renamed + "\"},\"description\":{\"en\":\"Plans the leave\"},\"version\":0}").andExpect(status().isOk()));

        assertThat(updated.get("name").get("en").asText()).isEqualTo(renamed);
        assertThat(updated.get("code").asText()).isEqualTo(created.get("code").asText());   // the code never changes
        assertThat(updated.get("permVersion").asInt()).isEqualTo(1);
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        assertThat(matrix(updated)).isEqualTo(Map.of("leave", List.of("view")));

        send(put("/api/v1/admin/roles/" + id), "{\"name\":{\"en\":\"HR\"},\"version\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));   // a system role has that name
    }

    // ------------------------------------------------------------------ the permission table

    @Test
    void thePermissionTableSavesAndPermVersionOnlyMovesWhenSomethingChanged() throws Exception {
        JsonNode role = createRole(unique("Exam office"), "dashboard", "leave", "shifts", "announcements");
        String id = role.get("id").asText();

        JsonNode saved = body(savePermissions(id, "{\"dashboard\":[\"view\",\"export\"],\"leave\":[\"view\",\"edit\"],"
                + "\"shifts\":[\"view\",\"edit\"],\"announcements\":[\"view\",\"edit\"]}", 0).andExpect(status().isOk()));

        assertThat(matrix(saved)).isEqualTo(Map.of("dashboard", List.of("view", "export"), "shifts", List.of("view", "edit"),
                "leave", List.of("view", "edit"), "announcements", List.of("view", "edit")));
        assertThat(saved.get("permVersion").asInt()).isEqualTo(2);
        assertThat(saved.get("version").asLong()).isEqualTo(1);
        assertThat(matrix(body(get("/api/v1/admin/roles/" + id)))).isEqualTo(matrix(saved));   // really stored

        // saving the same table again changes nothing, not even the version
        JsonNode again = body(savePermissions(id, "{\"dashboard\":[\"export\",\"view\"],\"leave\":[\"edit\",\"view\"],"
                + "\"shifts\":[\"view\",\"edit\"],\"announcements\":[\"view\",\"edit\"]}", 1).andExpect(status().isOk()));
        assertThat(again.get("permVersion").asInt()).isEqualTo(2);
        assertThat(again.get("version").asLong()).isEqualTo(1);
    }

    @Test
    void removingViewClearsTheWholeRow_andARowCannotKeepActionsWithoutView() throws Exception {
        JsonNode role = createRole(unique("Planner"), "leave", "shifts");
        String id = role.get("id").asText();
        savePermissions(id, "{\"leave\":[\"view\",\"create\",\"edit\",\"delete\",\"export\"],\"shifts\":[\"view\",\"edit\"]}", 0)
                .andExpect(status().isOk());

        // the page unticks View of "leave": the whole row goes
        JsonNode cleared = body(savePermissions(id, "{\"shifts\":[\"view\",\"edit\"]}", 1).andExpect(status().isOk()));
        assertThat(matrix(cleared)).isEqualTo(Map.of("shifts", List.of("view", "edit")));
        assertThat(cleared.get("permVersion").asInt()).isEqualTo(3);

        // but a row that keeps Edit without View is refused
        savePermissions(id, "{\"shifts\":[\"edit\"]}", 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("permissions.shifts.view"))
                .andExpect(jsonPath("$.errors[0].rule").value("NeedsView"));
        assertThat(matrix(body(get("/api/v1/admin/roles/" + id)))).isEqualTo(Map.of("shifts", List.of("view", "edit")));   // unchanged
    }

    @Test
    void cellsThatDoNotApplyAreRefused() throws Exception {
        JsonNode role = createRole(unique("Odd"), "dashboard");
        String id = role.get("id").asText();

        savePermissions(id, "{\"dashboard\":[\"view\",\"create\"]}", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("permissions.dashboard.create"))
                .andExpect(jsonPath("$.errors[0].rule").value("NotApplicable"));
        savePermissions(id, "{\"qr\":[\"view\",\"approve\"]}", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("NotApplicable"));
        savePermissions(id, "{\"settings\":[\"view\",\"delete\"]}", 0).andExpect(status().isBadRequest());
        savePermissions(id, "{\"reports\":[\"view\",\"edit\"]}", 0).andExpect(status().isBadRequest());
        savePermissions(id, "{\"users\":[\"view\",\"export\"]}", 0).andExpect(status().isBadRequest());

        // every applicable cell is accepted
        savePermissions(id, json.writeValueAsString(APPLICABLE), 0).andExpect(status().isOk());
    }

    @Test
    void unknownNamesAndAllProblemsAreReportedTogether() throws Exception {
        JsonNode role = createRole(unique("Wrong"), "dashboard");
        ResultActions result = savePermissions(role.get("id").asText(),
                "{\"payroll\":[\"view\"],\"people\":[\"view\",\"fly\"],\"dashboard\":[\"create\"],\"leave\":[\"edit\"]}", 0);
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        JsonNode errors = body(result).get("errors");
        assertThat(errors).extracting(e -> e.get("field").asText() + ":" + e.get("rule").asText())
                .contains("permissions.payroll:UnknownModule", "permissions.people:UnknownAction",
                        "permissions.dashboard.create:NotApplicable",
                        "permissions.leave.view:NeedsView");
    }

    @Test
    void aStaleOrMissingVersionIsRefused() throws Exception {
        JsonNode role = createRole(unique("Versioned"), "people");
        String id = role.get("id").asText();
        savePermissions(id, "{\"people\":[\"view\",\"edit\"]}", 0).andExpect(status().isOk());

        savePermissions(id, "{\"people\":[\"view\"]}", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        send(put("/api/v1/admin/roles/" + id + "/permissions"), "{\"permissions\":{\"people\":[\"view\"]}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
        assertThat(matrix(body(get("/api/v1/admin/roles/" + id)))).isEqualTo(Map.of("people", List.of("view", "edit")));
    }

    // ------------------------------------------------------------------ your own role, deleting

    @Test
    void nobodyCanChangeOrDeleteTheRoleTheyHold() throws Exception {
        JsonNode role = createRole(unique("Self"), "people", "users");
        String id = role.get("id").asText();
        String code = role.get("code").asText();
        // the role may manage roles (so only the own-role rule can stop it)
        savePermissions(id, "{\"people\":[\"view\"],\"users\":[\"view\",\"edit\",\"delete\"]}", 0).andExpect(status().isOk());
        Caller holder = TestJwt.webUser(code);   // a user whose token says they have this role

        mvc.perform(put("/api/v1/admin/roles/" + id + "/permissions").header("Authorization", holder.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":{\"people\":[\"view\",\"edit\",\"delete\"],\"users\":[\"view\",\"edit\"]},\"version\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWN_ROLE"));
        mvc.perform(delete("/api/v1/admin/roles/" + id).header("Authorization", holder.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWN_ROLE"));

        // somebody else can
        savePermissions(id, "{\"people\":[\"view\",\"edit\"],\"users\":[\"view\"]}", 1).andExpect(status().isOk());
    }

    @Test
    void aDeletedRoleDisappearsButItsRecordIsKept() throws Exception {
        JsonNode role = createRole(unique("Short lived"), "leave");
        String id = role.get("id").asText();

        mvc.perform(delete("/api/v1/admin/roles/" + id).header("Authorization", superAdmin.bearer())).andExpect(status().isNoContent());

        assertThat(ids(list(superAdmin))).doesNotContain(id);
        mvc.perform(get("/api/v1/admin/roles/" + id).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("select inactive from role where id = ?", Boolean.class, UUID.fromString(id))).isTrue();
        mvc.perform(delete("/api/v1/admin/roles/" + id).header("Authorization", superAdmin.bearer())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ compare

    @Test
    void twoRolesCanBeComparedSideBySide() throws Exception {
        String hr = role("hr").get("id").asText();
        String viewer = role("viewer").get("id").asText();

        JsonNode diff = body(get("/api/v1/admin/roles/compare?a=" + hr + "&b=" + viewer));

        assertThat(diff.get("a").get("code").asText()).isEqualTo("hr");
        assertThat(diff.get("b").get("code").asText()).isEqualTo("viewer");
        assertThat(diff.get("modules")).hasSize(12);

        JsonNode people = module(diff, "people");
        assertThat(strings(people.get("a"))).containsExactly("view", "create", "edit", "delete", "export");
        assertThat(strings(people.get("b"))).containsExactly("view");
        assertThat(strings(people.get("onlyA"))).containsExactly("create", "edit", "delete", "export");
        assertThat(strings(people.get("onlyB"))).isEmpty();
        assertThat(people.get("same").asBoolean()).isFalse();

        JsonNode qr = module(diff, "qr");                       // viewer can view QR, HR has no QR access at all
        assertThat(strings(qr.get("onlyA"))).isEmpty();
        assertThat(strings(qr.get("onlyB"))).containsExactly("view");

        assertThat(module(diff, "users").get("same").asBoolean()).isTrue();    // neither has it
        assertThat(module(diff, "dashboard").get("same").asBoolean()).isFalse();

        mvc.perform(get("/api/v1/admin/roles/compare?a=" + hr + "&b=" + UUID.randomUUID()).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/roles/compare?a=" + hr).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ who may call, other schools

    @Test
    void onlyOfficeUsersMayManageRoles() throws Exception {
        mvc.perform(get("/api/v1/admin/roles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/roles").header("Authorization", TestJwt.appUser("admin").bearer())).andExpect(status().isForbidden());
    }

    @Test
    void everySchoolHasItsOwnRoles_andParallelFirstCallsSeedThemOnlyOnce() throws Exception {
        UUID otherSchool = UUID.randomUUID();
        jdbc.update("insert into school (id, code, name_en, created_at, updated_at) values (?, ?, 'Other School', now(), now())",
                otherSchool, "T" + otherSchool.toString().substring(0, 8));
        try {
            Caller theirAdmin = TestJwt.webUser("super_admin", otherSchool);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {   // eight requests at the very same time, the first use of this school
                calls.add(pool.submit(() -> list(theirAdmin).size()));
            }
            for (Future<Integer> call : calls) {
                assertThat(call.get()).isEqualTo(4);
            }
            pool.shutdown();

            assertThat(jdbc.queryForObject("select count(*) from role where school_id = ?", Integer.class, otherSchool)).isEqualTo(4);
            // our own roles are separate rows, and a custom role of ours is invisible to them
            createRole(unique("Ours only"), "leave");
            assertThat(names(list(theirAdmin))).doesNotContain("Ours only");
            assertThat(ids(list(theirAdmin))).doesNotContainAnyElementsOf(ids(list(superAdmin)));
        } finally {
            jdbc.update("delete from role where school_id = ?", otherSchool);   // role_permission goes with it
            jdbc.update("delete from school where id = ?", otherSchool);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions send(MockHttpServletRequestBuilder request, String content) throws Exception {
        return mvc.perform(request.header("Authorization", superAdmin.bearer()).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions savePermissions(String id, String permissionsJson, long version) throws Exception {
        return send(put("/api/v1/admin/roles/" + id + "/permissions"),
                "{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}");
    }

    private JsonNode createRole(String name, String... viewModules) throws Exception {
        String modules = String.join("\",\"", viewModules);
        return body(send(post("/api/v1/admin/roles"),
                "{\"name\":{\"en\":\"" + name + "\"},\"viewModules\":[\"" + modules + "\"]}").andExpect(status().isCreated()));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", superAdmin.bearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<JsonNode> list(Caller caller) throws Exception {
        JsonNode all = json.readTree(mvc.perform(get("/api/v1/admin/roles").header("Authorization", caller.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<JsonNode> roles = new ArrayList<>();
        all.forEach(roles::add);
        return roles;
    }

    private static List<JsonNode> systemRoles(List<JsonNode> roles) {
        return roles.stream().filter(r -> r.get("system").asBoolean()).toList();
    }

    private JsonNode role(String code) throws Exception {
        return list(superAdmin).stream().filter(r -> r.get("code").asText().equals(code)).findFirst().orElseThrow();
    }

    private static List<String> names(List<JsonNode> roles) {
        return roles.stream().map(r -> r.get("name").get("en").asText()).toList();
    }

    private static List<String> ids(List<JsonNode> roles) {
        return roles.stream().map(r -> r.get("id").asText()).toList();
    }

    private static JsonNode module(JsonNode diff, String module) {
        for (JsonNode m : diff.get("modules")) {
            if (m.get("module").asText().equals(module)) {
                return m;
            }
        }
        throw new AssertionError("no module " + module);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, List<String>> matrix(JsonNode role) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        role.get("permissions").fields().forEachRemaining(e -> out.put(e.getKey(), strings(e.getValue())));
        return out;
    }
}
