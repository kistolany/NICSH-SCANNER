package kh.cmt.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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

/**
 * Step 4.1 "done when": Add person (steps 1–2 of the wizard: personal info and work) and Edit person save; an
 * inactive person cannot log in. Each test works in a school of its own, so Staff IDs and lists are predictable.
 */
@ApiTest
class PeopleTest {

    private static final String PEOPLE = "/api/v1/admin/people";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private UUID school;
    private Caller admin;
    private UUID primary;
    private UUID teacherPosition;

    @BeforeEach
    void schoolOfItsOwn() throws Exception {
        school = UUID.randomUUID();
        jdbc.sql("insert into school (id, code, name_en, created_at, updated_at) values (:id, :c, 'People school', now(), now())")
                .param("id", school).param("c", "P" + school.toString().substring(0, 8)).update();
        admin = TestJwt.webUser("super_admin", school);
        primary = org("departments", "{\"name\":{\"en\":\"Primary school\",\"km\":\"បឋមសិក្សា\"},\"color\":\"#2563eb\"}");
        teacherPosition = org("positions", "{\"name\":{\"en\":\"Teacher\"}}");
    }

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void addPersonAndEditPersonSave() throws Exception {
        Map<String, Object> form = new HashMap<>(Map.of(
                "name", Map.of("km", "សុខា លីម", "en", "Sokha  Lim", "ar", "سوخا ليم"),
                "gender", "female", "dateOfBirth", "1992-05-14", "nationality", "kh", "nationalId", "010203040",
                "maritalStatus", "married", "bloodType", "o+", "phone", "012 345 678", "email", "Sokha.Lim@cmtschool.edu.kh",
                "role", "teacher"));
        form.putAll(Map.of("departmentId", primary, "positionId", teacherPosition, "employmentType", "full_time",
                "startDate", "2026-10-12", "probationEndDate", "2027-01-12",
                "address", Map.of("province", "Phnom Penh", "district", "Chamkar Mon", "street", "St. 271"),
                "emergencyContact", Map.of("name", "Dara Lim", "relation", "Husband", "phone", "098 765 432")));

        JsonNode created = body(create(admin, form).andExpect(status().isCreated()));
        assertThat(created.get("staffId").asText()).isEqualTo("CMT-0001");   // handed out automatically
        assertThat(created.get("name").get("km").asText()).isEqualTo("សុខា លីម");
        assertThat(created.get("name").get("en").asText()).isEqualTo("Sokha Lim");
        assertThat(created.get("nationality").asText()).isEqualTo("KH");
        assertThat(created.get("bloodType").asText()).isEqualTo("O+");
        assertThat(created.get("email").asText()).isEqualTo("sokha.lim@cmtschool.edu.kh");
        assertThat(created.get("department").get("name").get("km").asText()).isEqualTo("បឋមសិក្សា");
        assertThat(created.get("department").get("color").asText()).isEqualTo("#2563EB");
        assertThat(created.get("position").get("name").get("en").asText()).isEqualTo("Teacher");
        assertThat(created.has("subjects")).isFalse();   // no subjects / classes (teacher features removed)
        assertThat(created.get("address").get("district").asText()).isEqualTo("Chamkar Mon");
        assertThat(created.get("emergencyContact").get("relation").asText()).isEqualTo("Husband");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("hasLogin").asBoolean()).isFalse();

        // the next one gets the next number
        assertThat(body(create(admin, minimal("Dara Chan", "staff")).andExpect(status().isCreated())).get("staffId").asText())
                .isEqualTo("CMT-0002");

        // Edit person: the whole form with the version that was read
        ObjectNode edit = form(created);
        edit.put("role", "staff");
        edit.put("phone", "011 222 333");
        JsonNode saved = body(update(admin, created.get("id").asText(), edit).andExpect(status().isOk()));
        assertThat(saved.get("role").asText()).isEqualTo("staff");
        assertThat(saved.get("phone").asText()).isEqualTo("011 222 333");
        assertThat(saved.get("staffId").asText()).isEqualTo("CMT-0001");   // a new role keeps the ID
        assertThat(saved.get("version").asLong()).isGreaterThan(created.get("version").asLong());

        update(admin, created.get("id").asText(), edit).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        JsonNode read = body(mvc.perform(get(PEOPLE + "/" + created.get("id").asText()).header("Authorization", admin.bearer())));
        assertThat(read.get("phone").asText()).isEqualTo("011 222 333");
    }

    @Test
    void anInactivePersonCannotLogIn() throws Exception {
        JsonNode person = body(create(admin, minimal("Vanna Kim", "staff")).andExpect(status().isCreated()));
        UUID personId = UUID.fromString(person.get("id").asText());
        UUID account = appAccountFor(personId);   // what step 4.2 will create
        Caller app = TestJwt.withPerson("app", "staff", school, account, personId);
        mvc.perform(get("/api/v1/app/me/profile").header("Authorization", app.bearer())).andExpect(status().isOk());
        Thread.sleep(1100);   // sign-outs are compared by the second the token was issued in

        // the reason is required
        inactivate(person, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        inactivate(person, "{\"reason\":\"resigned\",\"lastWorkingDay\":\"2026-10-31\"}").andExpect(status().isNoContent());

        // the account is inactive (the auth server refuses it) and the token issued before is refused here at once
        assertThat(accountStatus(account)).isEqualTo("inactive");
        mvc.perform(get("/api/v1/app/me/profile").header("Authorization", app.bearer())).andExpect(status().isUnauthorized());

        JsonNode now = body(mvc.perform(get(PEOPLE + "/" + personId).header("Authorization", admin.bearer())));
        assertThat(now.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(now.get("inactivation").get("reason").asText()).isEqualTo("RESIGNED");
        assertThat(now.get("inactivation").get("lastWorkingDay").asText()).isEqualTo("2026-10-31");
        assertThat(now.get("hasLogin").asBoolean()).isTrue();
        assertThat(list(Map.of()).get("total").asInt()).isZero();
        assertThat(list(Map.of("status", "inactive")).get("total").asInt()).isEqualTo(1);
        // an inactive person is only restored, not edited
        update(admin, personId.toString(), form(now)).andExpect(jsonPath("$.code").value("CONFLICT"));

        // restored without login: still cannot sign in; then with login
        mvc.perform(post(PEOPLE + "/" + personId + "/restore").header("Authorization", admin.bearer())).andExpect(status().isOk());
        assertThat(accountStatus(account)).isEqualTo("inactive");
        inactivate(person, "{\"reason\":\"MISTAKE\"}").andExpect(status().isNoContent());
        mvc.perform(post(PEOPLE + "/" + personId + "/restore").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowLogin\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(accountStatus(account)).isEqualTo("active");
    }

    // ------------------------------------------------------------------ the form

    @Test
    void wrongFormsAreRefused() throws Exception {
        expectErrors(create(admin, Map.of()), "name.km:NotBlank", "name.en:NotBlank", "role:NotNull");
        Map<String, Object> bad = new HashMap<>(minimal("X Y", "staff"));
        bad.putAll(Map.of("gender", "other", "maritalStatus", "it's complicated", "bloodType", "C+", "nationality", "XX",
                "email", "not-an-email", "phone", "call me", "employmentType", "forever", "dateOfBirth", "2999-01-01"));
        expectErrors(create(admin, bad), "gender:OneOf", "maritalStatus:OneOf", "bloodType:OneOf", "nationality:Country",
                "email:Email", "phone:Pattern", "employmentType:OneOf", "dateOfBirth:Range");
        Map<String, Object> dates = new HashMap<>(minimal("X Y", "staff"));
        dates.putAll(Map.of("startDate", "2026-10-12", "contractEndDate", "2026-01-01", "probationEndDate", "2026-01-01"));
        expectErrors(create(admin, dates), "contractEndDate:BeforeStart", "probationEndDate:BeforeStart");

        // picked items must exist and be active
        Map<String, Object> unknown = new HashMap<>(minimal("X Y", "teacher"));
        unknown.putAll(Map.of("departmentId", UUID.randomUUID(), "positionId", UUID.randomUUID()));
        expectErrors(create(admin, unknown), "departmentId:UnknownDepartment", "positionId:UnknownPosition");
    }

    @Test
    void aRetiredDepartmentCannotBePickedButStaysOnTheRecord() throws Exception {
        UUID office = org("departments", "{\"name\":{\"en\":\"Office\"}}");
        Map<String, Object> form = new HashMap<>(minimal("Rotha Mam", "staff"));
        form.put("departmentId", office);
        JsonNode person = body(create(admin, form).andExpect(status().isCreated()));
        mvc.perform(delete("/api/v1/admin/departments/" + office).header("Authorization", admin.bearer())).andExpect(status().isNoContent());

        create(admin, form).andExpect(status().isBadRequest());   // not for a new record
        JsonNode saved = body(update(admin, person.get("id").asText(), form(person).put("email", "rotha@cmt.school"))
                .andExpect(status().isOk()));                           // kept on the old one
        assertThat(saved.get("department").get("inactive").asBoolean()).isTrue();
    }

    @Test
    void staffIdsAreUniqueAndOnlyASuperAdminTypesThem() throws Exception {
        Caller hr = TestJwt.webUser("hr", school);
        Map<String, Object> typed = new HashMap<>(minimal("Typed Id", "staff"));
        typed.put("staffId", "cmt-0003");
        create(hr, typed).andExpect(status().isForbidden());
        JsonNode first = body(create(admin, typed).andExpect(status().isCreated()));
        assertThat(first.get("staffId").asText()).isEqualTo("CMT-0003");
        create(admin, typed).andExpect(jsonPath("$.code").value("STAFF_ID_TAKEN"));   // case does not matter

        // automatic numbers skip the typed one
        assertThat(body(create(hr, minimal("A One", "staff"))).get("staffId").asText()).isEqualTo("CMT-0001");
        assertThat(body(create(hr, minimal("A Two", "staff"))).get("staffId").asText()).isEqualTo("CMT-0002");
        assertThat(body(create(hr, minimal("A Three", "staff"))).get("staffId").asText()).isEqualTo("CMT-0004");

        // "Edit ID": Super Admin only, and still unique
        JsonNode edited = body(update(admin, first.get("id").asText(), form(first).put("staffId", "CMT-9000")).andExpect(status().isOk()));
        assertThat(edited.get("staffId").asText()).isEqualTo("CMT-9000");
        update(hr, first.get("id").asText(), form(edited).put("staffId", "CMT-9001")).andExpect(status().isForbidden());
        update(admin, first.get("id").asText(), form(edited).put("staffId", "cmt-0001")).andExpect(jsonPath("$.code").value("STAFF_ID_TAKEN"));
        // an ID is never used twice, not even after its person was made inactive
        inactivate(edited, "{\"reason\":\"OTHER\"}").andExpect(status().isNoContent());
        Map<String, Object> again = new HashMap<>(minimal("Reuse", "staff"));
        again.put("staffId", "CMT-9000");
        create(admin, again).andExpect(jsonPath("$.code").value("STAFF_ID_TAKEN"));
    }

    @Test
    void theNationalIdIsUniqueAndSensitive() throws Exception {
        Map<String, Object> form = new HashMap<>(minimal("Malis Nhem", "staff"));
        form.putAll(Map.of("nationalId", "ab-123456", "phone", "012 000 111"));
        JsonNode person = body(create(admin, form).andExpect(status().isCreated()));
        assertThat(person.get("nationalId").asText()).isEqualTo("AB-123456");
        Map<String, Object> twin = new HashMap<>(minimal("Twin", "staff"));
        twin.put("nationalId", "AB-123456");
        create(admin, twin).andExpect(jsonPath("$.code").value("NATIONAL_ID_TAKEN"));

        // Viewer: People view, phone numbers yes, national ID no
        Caller viewer = TestJwt.webUser("viewer", school);
        JsonNode seen = body(mvc.perform(get(PEOPLE + "/" + person.get("id").asText()).header("Authorization", viewer.bearer())));
        assertThat(seen.get("nationalId").asText()).isEqualTo("••••");
        assertThat(seen.get("phone").asText()).isEqualTo("012 000 111");

        // a role that may edit people but not see national IDs: the mask sent back keeps the number
        Caller clerk = clerkWithoutNationalIds();
        JsonNode clerkSees = body(mvc.perform(get(PEOPLE + "/" + person.get("id").asText()).header("Authorization", clerk.bearer())));
        update(clerk, person.get("id").asText(), form(clerkSees).put("email", "malis@cmt.school")).andExpect(status().isOk());
        assertThat(jdbc.sql("select national_id from person where id = :id").param("id", UUID.fromString(person.get("id").asText()))
                .query(String.class).single()).isEqualTo("AB-123456");
    }

    @Test
    void theSupervisorIsAnotherActivePersonAndNeverAChainBack() throws Exception {
        JsonNode lead = body(create(admin, minimal("Sokha Lead", "staff")));
        Map<String, Object> form = new HashMap<>(minimal("Dara Member", "staff"));
        form.put("supervisorId", lead.get("id").asText());
        JsonNode member = body(create(admin, form).andExpect(status().isCreated()));
        assertThat(member.get("supervisor").get("staffId").asText()).isEqualTo(lead.get("staffId").asText());
        assertThat(member.get("supervisor").get("name").get("en").asText()).isEqualTo("Sokha Lead");

        expectErrors(update(admin, lead.get("id").asText(), form(lead).put("supervisorId", member.get("id").asText())), "supervisorId:Loop");
        expectErrors(update(admin, lead.get("id").asText(), form(lead).put("supervisorId", lead.get("id").asText())), "supervisorId:Self");
        Map<String, Object> stranger = new HashMap<>(minimal("X Y", "staff"));
        stranger.put("supervisorId", UUID.randomUUID().toString());
        expectErrors(create(admin, stranger), "supervisorId:UnknownPerson");
    }

    // ------------------------------------------------------------------ inactive, restore, delete forever

    @Test
    void nobodyMovesTheirOwnPersonToInactive() throws Exception {
        JsonNode me = body(create(admin, minimal("Dara Chan", "admin")));
        Caller myself = TestJwt.withPerson("web", "super_admin", school, UUID.randomUUID(), UUID.fromString(me.get("id").asText()));
        inactivate(me, "{\"reason\":\"OTHER\"}", myself).andExpect(jsonPath("$.code").value("OWN_ACCOUNT"));
    }

    @Test
    void theLastWorkingDayIsNotBeforeTheStart() throws Exception {
        Map<String, Object> form = new HashMap<>(minimal("Started", "staff"));
        form.put("startDate", "2026-01-05");
        JsonNode person = body(create(admin, form).andExpect(status().isCreated()));
        expectErrors(inactivate(person, "{\"reason\":\"QUIT\",\"lastWorkingDay\":\"2025-12-31\"}"), "reason:OneOf", "lastWorkingDay:BeforeStart");
    }

    @Test
    void deleteForeverOnlyForInactivePeopleWithoutHistoryOrLogin() throws Exception {
        JsonNode lone = body(create(admin, minimal("Lone Person", "staff")));
        String id = lone.get("id").asText();
        mvc.perform(delete(PEOPLE + "/" + id).header("Authorization", admin.bearer())).andExpect(jsonPath("$.code").value("NOT_INACTIVE"));
        inactivate(lone, "{\"reason\":\"MISTAKE\"}").andExpect(status().isNoContent());
        mvc.perform(delete(PEOPLE + "/" + id).header("Authorization", TestJwt.webUser("hr", school).bearer()))
                .andExpect(status().isForbidden());   // HR may delete people, but forever is for Super Admin
        mvc.perform(delete(PEOPLE + "/" + id).header("Authorization", admin.bearer())).andExpect(status().isNoContent());
        mvc.perform(get(PEOPLE + "/" + id).header("Authorization", admin.bearer())).andExpect(status().isNotFound());

        // still supervising somebody: in use
        JsonNode lead = body(create(admin, minimal("Team Lead", "staff")));
        Map<String, Object> member = new HashMap<>(minimal("Team Member", "staff"));
        member.put("supervisorId", lead.get("id").asText());
        create(admin, member).andExpect(status().isCreated());
        inactivate(lead, "{\"reason\":\"RESIGNED\"}").andExpect(status().isNoContent());
        mvc.perform(delete(PEOPLE + "/" + lead.get("id").asText()).header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("IN_USE"));

        // has a login account: in use
        JsonNode withLogin = body(create(admin, minimal("Has Login", "staff")));
        appAccountFor(UUID.fromString(withLogin.get("id").asText()));
        inactivate(withLogin, "{\"reason\":\"RESIGNED\"}").andExpect(status().isNoContent());
        mvc.perform(delete(PEOPLE + "/" + withLogin.get("id").asText()).header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.code").value("IN_USE"));
    }

    // ------------------------------------------------------------------ the list

    @Test
    void theListSearchesInEveryScriptFiltersSortsAndPages() throws Exception {
        UUID office = org("departments", "{\"name\":{\"en\":\"Office\"}}");
        Map<String, Object> sokha = new HashMap<>(Map.of("name", Map.of("km", "សុខា", "en", "Sokha Lim", "ar", "سوخا"), "role", "teacher",
                "departmentId", primary, "startDate", "2020-01-01"));
        create(admin, sokha).andExpect(status().isCreated());
        Map<String, Object> dara = new HashMap<>(Map.of("name", Map.of("km", "តារា", "en", "Dara Chan"), "role", "staff",
                "departmentId", office, "startDate", "2024-01-01"));
        create(admin, dara).andExpect(status().isCreated());
        create(admin, minimal("Bopha Ly", "admin")).andExpect(status().isCreated());

        assertThat(names(list(Map.of()))).containsExactly("Bopha Ly", "Dara Chan", "Sokha Lim");   // by name
        assertThat(names(list(Map.of("q", "សុខា")))).containsExactly("Sokha Lim");                  // Khmer
        assertThat(names(list(Map.of("q", "سوخا")))).containsExactly("Sokha Lim");                  // Arabic
        assertThat(names(list(Map.of("q", "cmt-0002")))).containsExactly("Dara Chan");              // Staff ID
        assertThat(names(list(Map.of("dept", office.toString())))).containsExactly("Dara Chan");
        assertThat(names(list(Map.of("role", "teacher")))).containsExactly("Sokha Lim");
        assertThat(names(list(Map.of("sort", "-startDate")))).containsExactly("Dara Chan", "Sokha Lim", "Bopha Ly");
        JsonNode page = list(Map.of("size", "2", "page", "1"));
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(names(page)).containsExactly("Sokha Lim");
        assertThat(list(Map.of()).get("items").get(1).get("department").get("name").get("en").asText()).isEqualTo("Office");

        mvc.perform(get(PEOPLE).param("sort", "salary").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());
        mvc.perform(get(PEOPLE).param("size", "1000").header("Authorization", admin.bearer())).andExpect(status().isBadRequest());

        // another school sees none of them, and cannot open one
        Caller other = TestJwt.webUser("super_admin");
        String id = list(Map.of()).get("items").get(0).get("id").asText();
        mvc.perform(get(PEOPLE + "/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void departmentsPositionsAndRolesCountTheirPeople() throws Exception {
        Map<String, Object> form = new HashMap<>(minimal("Counted", "teacher"));
        form.putAll(Map.of("departmentId", primary, "positionId", teacherPosition));
        create(admin, form).andExpect(status().isCreated());
        create(admin, form).andExpect(status().isCreated());
        create(admin, minimal("Elsewhere", "staff")).andExpect(status().isCreated());

        JsonNode departments = body(mvc.perform(get("/api/v1/admin/departments").header("Authorization", admin.bearer())));
        assertThat(departments.get("items").get(0).get("people").asInt()).isEqualTo(2);
        JsonNode position = body(mvc.perform(get("/api/v1/admin/positions/" + teacherPosition).header("Authorization", admin.bearer())));
        assertThat(position.get("people").asInt()).isEqualTo(2);

        // "Sees: N people" on the roles: all school, or the selected departments
        JsonNode roles = body(mvc.perform(get("/api/v1/admin/roles").header("Authorization", admin.bearer())));
        assertThat(roles.get(0).get("code").asText()).isEqualTo("super_admin");
        assertThat(roles.get(0).get("sees").asInt()).isEqualTo(3);
        JsonNode created = body(mvc.perform(post("/api/v1/admin/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"Primary only\"},\"scope\":{\"type\":\"SELECTED_DEPARTMENTS\","
                        + "\"departmentIds\":[\"" + primary + "\"]}}")));
        assertThat(created.get("sees").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the app

    @Test
    void theAppShowsTheirOwnProfile() throws Exception {
        Map<String, Object> form = new HashMap<>(minimal("Sokha Lim", "teacher"));
        form.putAll(Map.of("departmentId", primary, "positionId", teacherPosition, "phone", "012 345 678"));
        JsonNode person = body(create(admin, form).andExpect(status().isCreated()));
        Caller app = TestJwt.withPerson("app", "teacher", school, UUID.randomUUID(), UUID.fromString(person.get("id").asText()));

        JsonNode profile = body(mvc.perform(get("/api/v1/app/me/profile").header("Authorization", app.bearer())).andExpect(status().isOk()));
        assertThat(profile.get("staffId").asText()).isEqualTo(person.get("staffId").asText());
        assertThat(profile.get("name").get("en").asText()).isEqualTo("Sokha Lim");
        assertThat(profile.get("department").get("en").asText()).isEqualTo("Primary school");
        assertThat(profile.get("position").get("en").asText()).isEqualTo("Teacher");
        assertThat(profile.get("phone").asText()).isEqualTo("012 345 678");

        // no linked person: nothing to show; office users use the People page
        mvc.perform(get("/api/v1/app/me/profile").header("Authorization", TestJwt.appUser("staff").bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/me/profile").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> minimal(String nameEn, String role) {
        return Map.of("name", Map.of("km", "ឈ្មោះ", "en", nameEn), "role", role);
    }

    private ResultActions create(Caller caller, Map<String, Object> form) throws Exception {
        return mvc.perform(post(PEOPLE).header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form)));
    }

    private ResultActions update(Caller caller, String id, Object form) throws Exception {
        return mvc.perform(put(PEOPLE + "/" + id).header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form)));
    }

    private ResultActions inactivate(JsonNode person, String body) throws Exception {
        return inactivate(person, body, admin);
    }

    private ResultActions inactivate(JsonNode person, String body, Caller caller) throws Exception {
        return mvc.perform(post(PEOPLE + "/" + person.get("id").asText() + "/inactivate").header("Authorization", caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** The form as the Edit page would send it back: what was read, ids instead of names, with the version. */
    private ObjectNode form(JsonNode person) {
        ObjectNode form = person.deepCopy();
        for (String gone : List.of("id", "department", "position", "supervisor", "status", "inactivation",
                "hasLogin", "createdAt", "updatedAt")) {
            form.remove(gone);
        }
        if (person.has("department")) {
            form.put("departmentId", person.get("department").get("id").asText());
        }
        if (person.has("position")) {
            form.put("positionId", person.get("position").get("id").asText());
        }
        if (person.has("supervisor")) {
            form.put("supervisorId", person.get("supervisor").get("id").asText());
        }
        return form;
    }

    private JsonNode list(Map<String, String> params) throws Exception {
        var request = get(PEOPLE).header("Authorization", admin.bearer());
        params.forEach(request::param);
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(p -> names.add(p.get("name").get("en").asText()));
        return names;
    }

    private UUID org(String kind, String body) throws Exception {
        return UUID.fromString(body(mvc.perform(post("/api/v1/admin/" + kind).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())).get("id").asText());
    }

    /** An app login account for the person, as step 4.2 will create it. */
    private UUID appAccountFor(UUID personId) {
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                        insert into auth.account (id, school_id, username, password_hash, type, person_id, status, created_at, updated_at)
                        values (:id, :s, :u, 'x', 'app', :p, 'active', now(), now())""")
                .param("id", account).param("s", school).param("u", "app." + suffix()).param("p", personId).update();
        return account;
    }

    private String accountStatus(UUID account) {
        return jdbc.sql("select status from auth.account where id = :id").param("id", account).query(String.class).single();
    }

    /** A custom role with People view + edit but without "national ID & documents". */
    private Caller clerkWithoutNationalIds() throws Exception {
        JsonNode role = body(mvc.perform(post("/api/v1/admin/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"People clerk\"},\"sensitiveFields\":"
                        + "{\"salaryBank\":false,\"nationalId\":false,\"phone\":true,\"idScans\":false}}")).andExpect(status().isCreated()));
        mvc.perform(put("/api/v1/admin/roles/" + role.get("id").asText() + "/permissions").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":{\"people\":[\"view\",\"edit\"]},\"version\":" + role.get("version") + "}"))
                .andExpect(status().isOk());
        return TestJwt.webUser(role.get("code").asText(), school);
    }

    private void expectErrors(ResultActions result, String... fieldRules) throws Exception {
        JsonNode problem = body(result.andExpect(status().isBadRequest()));
        List<String> found = new ArrayList<>();
        problem.get("errors").forEach(e -> found.add(e.get("field").asText() + ":" + e.get("rule").asText()));
        assertThat(found).contains(fieldRules);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
