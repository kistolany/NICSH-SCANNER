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
import java.util.function.Supplier;
import kh.cmt.shared.security.RecordScope;
import kh.cmt.shared.security.RecordScope.SqlCondition;
import kh.cmt.shared.security.RecordScopes;
import kh.cmt.shared.security.ScopeFilter;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.people.domain.Person;
import kh.cmt.people.infrastructure.PersonRepository;
import kh.cmt.support.TestRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Step 3.3 "done when": a "Primary school only" user sees only primary people in every list and export.
 * <p>
 * The queries run on the real person table ({@link Person}); other tests add people to the same school, so every
 * query is narrowed to the six people made here. The role and its scope are set through the real API. The People
 * list endpoint itself is covered in {@code PeopleTest}.
 */
@ApiTest
class RecordScopeTest {

    private static final String ROLES = "/api/v1/admin/roles";
    private static final UUID SCHOOL = TestJwt.SCHOOL;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PersonRepository people;

    @Autowired
    RecordScopes scopes;

    @Autowired
    StringRedisTemplate redis;

    private final Caller superAdmin = TestJwt.webUser("super_admin");
    private final List<UUID> departments = new ArrayList<>();

    private UUID primary;
    private UUID secondary;
    private UUID office;

    // Primary: Sokha (team lead), Dara and Vanna (Sokha's team). Secondary: Chenda, Piseth (in Sokha's team too).
    // Office: Rotha.
    private final Map<String, UUID> person = new LinkedHashMap<>();

    @BeforeEach
    void schoolWithThreeDepartments() throws Exception {
        primary = department("Primary school");
        secondary = department("Secondary school");
        office = department("Front office");
        add("Sokha", primary, null);
        add("Dara", primary, "Sokha");
        add("Vanna", primary, "Sokha");
        add("Chenda", secondary, null);
        add("Piseth", secondary, "Sokha");
        add("Rotha", office, null);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.sql("delete from person where id in (:ids)").param("ids", person.values()).update();
        // custom roles go back to all school, with a new version so nothing cached about them is used again
        jdbc.sql("update role set scope_type = 'ALL', perm_version = perm_version + 1 where not system").update();
        jdbc.sql("delete from role_scope_department").update();
        redis.delete(redis.keys("perm:ver:*"));
        for (UUID id : departments) {
            jdbc.sql("delete from department where id = :id").param("id", id).update();
        }
    }

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void aPrimarySchoolOnlyUserSeesOnlyPrimaryPeopleInEveryListAndExport() throws Exception {
        String role = primarySchoolOnlyRole();

        asWebUser(role, null, () -> {
            assertThat(names(people.findAll(mine()))).containsExactly("Dara", "Sokha", "Vanna");

            Page<Person> page = people.findAll(mine(), PageRequest.of(0, 2));
            assertThat(page.getTotalElements()).isEqualTo(3);   // paging counts only what may be seen
            assertThat(page.getContent()).hasSize(2);
            assertThat(people.count(mine())).isEqualTo(3);

            assertThat(names(people.findAll(mine().and(named("a"))))).containsExactly("Dara", "Sokha", "Vanna");   // not Chenda, Rotha
            assertThat(people.findAll(mine().and(named("Chenda")))).isEmpty();

            assertThat(people.findById(person.get("Chenda"))).isEmpty();   // opening one by id: not found
            assertThat(people.findById(person.get("Dara"))).isPresent();
            assertThat(people.existsById(person.get("Rotha"))).isFalse();

            assertThat(export()).containsExactly("Dara", "Sokha", "Vanna");   // exports in plain SQL
            return null;
        });
    }

    @Test
    void theOtherScopeTypes() throws Exception {
        assertThat(asWebUser("super_admin", null, this::everybody)).hasSize(6);

        String ownDepartment = roleWithScope("Own department", "{\"type\":\"OWN_DEPARTMENT\"}");
        assertThat(asWebUser(ownDepartment, person.get("Chenda"), this::everybody)).containsExactly("Chenda", "Piseth");
        assertThat(asWebUser(ownDepartment, null, this::everybody)).isEmpty();   // no linked person: nobody

        String ownTeam = roleWithScope("Own team", "{\"type\":\"OWN_TEAM\"}");
        assertThat(asWebUser(ownTeam, person.get("Sokha"), this::everybody)).containsExactly("Dara", "Piseth", "Sokha", "Vanna");
        assertThat(asWebUser(ownTeam, person.get("Sokha"), this::export)).containsExactly("Dara", "Piseth", "Sokha", "Vanna");
        assertThat(asWebUser(ownTeam, null, this::everybody)).isEmpty();

        assertThat(as("app", "staff", person.get("Dara"), this::everybody)).containsExactly("Dara");   // app users: themselves
        assertThat(as("app", "teacher", null, this::everybody)).isEmpty();
        assertThat(as("app", "admin", person.get("Dara"), this::everybody)).hasSize(6);   // the admin app: all school
        assertThat(as("job", null, null, this::everybody)).hasSize(6);
        assertThat(asWebUser("no_such_role", null, this::everybody)).isEmpty();          // fails closed
        assertThat(everybody()).hasSize(6);                                              // no caller: scheduled work
    }

    @Test
    void wholeSchoolChecksCanStepOutsideTheScope() throws Exception {
        String role = primarySchoolOnlyRole();
        asWebUser(role, null, () -> {
            assertThat(ScopeFilter.withoutScope(() -> people.count(mine()))).isEqualTo(6);
            assertThat(people.count(mine())).isEqualTo(3);   // and back inside afterwards

            RecordScope scope = scopes.current();
            assertThat(scope.covers(person.get("Dara"), primary, null)).isTrue();
            assertThat(scope.covers(person.get("Chenda"), secondary, null)).isFalse();
            return null;
        });
    }

    @Test
    void aScopeChangeAppliesToTheNextQueryWithTheSameToken() throws Exception {
        String role = primarySchoolOnlyRole();
        assertThat(asWebUser(role, null, this::everybody)).containsExactly("Dara", "Sokha", "Vanna");

        JsonNode before = roleByCode(role);
        JsonNode after = body(saveScope(before.get("id").asText(),
                "{\"type\":\"SELECTED_DEPARTMENTS\",\"departmentIds\":[\"" + secondary + "\",\"" + office + "\"],\"version\":"
                        + before.get("version") + "}").andExpect(status().isOk()));
        assertThat(after.get("permVersion").asInt()).isEqualTo(before.get("permVersion").asInt() + 1);

        assertThat(asWebUser(role, null, this::everybody)).containsExactly("Chenda", "Piseth", "Rotha");
    }

    // ------------------------------------------------------------------ the scope on the Roles page

    @Test
    void theScopeIsSavedAndShownOnTheRole() throws Exception {
        JsonNode created = body(mvc.perform(post(ROLES).header("Authorization", superAdmin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"en\":\"Exam office " + suffix() + "\"},\"scope\":{\"type\":\"SELECTED_DEPARTMENTS\","
                                + "\"departmentIds\":[\"" + secondary + "\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope.type").value("SELECTED_DEPARTMENTS"))
                .andExpect(jsonPath("$.scope.departmentIds[0]").value(secondary.toString())));
        String id = created.get("id").asText();

        mvc.perform(get(ROLES + "/" + id).header("Authorization", superAdmin.bearer()))
                .andExpect(jsonPath("$.scope.departmentIds.length()").value(1));
        mvc.perform(get(ROLES).header("Authorization", superAdmin.bearer()))
                .andExpect(jsonPath("$[?(@.code == 'super_admin')].scope.type").value("ALL"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].scope.type").value("SELECTED_DEPARTMENTS"));

        // own team: departments are not used and not kept
        JsonNode team = body(saveScope(id, "{\"type\":\"OWN_TEAM\",\"departmentIds\":[\"" + primary + "\"],\"version\":"
                + created.get("version") + "}").andExpect(status().isOk()));
        assertThat(team.get("scope").get("type").asText()).isEqualTo("OWN_TEAM");
        assertThat(team.get("scope").get("departmentIds")).isEmpty();

        // the same scope again changes nothing: no new permission version
        JsonNode same = body(saveScope(id, "{\"type\":\"OWN_TEAM\",\"version\":" + team.get("version") + "}").andExpect(status().isOk()));
        assertThat(same.get("permVersion").asInt()).isEqualTo(team.get("permVersion").asInt());

        // compare shows both scopes
        String hrId = roleByCode("hr").get("id").asText();
        mvc.perform(get(ROLES + "/compare").param("a", id).param("b", hrId).header("Authorization", superAdmin.bearer()))
                .andExpect(jsonPath("$.a.scope.type").value("OWN_TEAM"))
                .andExpect(jsonPath("$.b.scope.type").value("ALL"));
    }

    @Test
    void wrongScopesAreRefused() throws Exception {
        JsonNode role = roleByCode(primarySchoolOnlyRole());
        String id = role.get("id").asText();
        long version = role.get("version").asLong();

        saveScope(id, "{\"type\":\"SELECTED_DEPARTMENTS\",\"departmentIds\":[],\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("scope.departmentIds"))
                .andExpect(jsonPath("$.errors[0].rule").value("NotEmpty"));
        saveScope(id, "{\"type\":\"SELECTED_DEPARTMENTS\",\"departmentIds\":[\"" + UUID.randomUUID() + "\"],\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("UnknownDepartment"));
        mvc.perform(delete("/api/v1/admin/departments/" + office).header("Authorization", superAdmin.bearer()))
                .andExpect(status().is2xxSuccessful());
        saveScope(id, "{\"type\":\"SELECTED_DEPARTMENTS\",\"departmentIds\":[\"" + office + "\"],\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rule").value("UnknownDepartment"));   // inactive: cannot be picked
        saveScope(id, "{\"departmentIds\":[],\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("scope.type"));
        saveScope(id, "{\"type\":\"ALL\"}").andExpect(status().isBadRequest());   // version missing
        saveScope(id, "{\"type\":\"ALL\",\"version\":" + (version + 7) + "}")
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        saveScope(roleByCode("hr").get("id").asText(), "{\"type\":\"ALL\",\"version\":0}")
                .andExpect(jsonPath("$.code").value("ROLE_LOCKED"));

        Caller viewer = TestJwt.webUser("viewer");
        mvc.perform(put(ROLES + "/" + id + "/scope").header("Authorization", viewer.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\",\"version\":" + version + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.action").value("edit"));

        // nobody changes the scope of the role they hold themselves
        Caller holder = TestJwt.webUser(role.get("code").asText());
        TestRoles.callerWithRole(mvc, json, role.get("name").get("en").asText(),
                Map.of("people", List.of("view"), "users", List.of("view", "edit")));
        long now = roleByCode(role.get("code").asText()).get("version").asLong();
        mvc.perform(put(ROLES + "/" + id + "/scope").header("Authorization", holder.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\",\"version\":" + now + "}"))
                .andExpect(jsonPath("$.code").value("OWN_ROLE"));
    }

    // ------------------------------------------------------------------ helpers

    /** The custom role "Primary school only": People view + export, scope = the primary department. */
    private String primarySchoolOnlyRole() throws Exception {
        return roleWithScope("Primary school only",
                "{\"type\":\"SELECTED_DEPARTMENTS\",\"departmentIds\":[\"" + primary + "\"]}");
    }

    private String roleWithScope(String name, String scope) throws Exception {
        Caller holder = TestRoles.callerWithRole(mvc, json, name, Map.of("people", List.of("view", "export")));
        String code = codeOf(holder);
        JsonNode role = roleByCode(code);
        String withVersion = scope.substring(0, scope.length() - 1) + ",\"version\":" + role.get("version") + "}";
        saveScope(role.get("id").asText(), withVersion).andExpect(status().isOk());
        return code;
    }

    private List<String> everybody() {
        return names(people.findAll(mine()));
    }

    /** The six people of this test (other tests add people to the same school). */
    private Specification<Person> mine() {
        List<UUID> ids = List.copyOf(person.values());
        return (root, query, cb) -> cb.and(cb.equal(root.get("schoolId"), SCHOOL), root.get("id").in(ids));
    }

    private static Specification<Person> named(String text) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("name").get("en")), "%" + text.toLowerCase() + "%");
    }

    /** What an export would write: plain SQL with the scope condition added. */
    private List<String> export() {
        SqlCondition scope = scopes.current().sql("p");
        return jdbc.sql("select p.name_en from person p where p.school_id = :school and p.id in (:ids) and " + scope.sql()
                        + " order by p.name_en")
                .params(scope.params()).param("school", SCHOOL).param("ids", person.values())
                .query(String.class).list();
    }

    private <T> T asWebUser(String role, UUID personId, Supplier<T> work) {
        return as("web", role, personId, work);
    }

    private <T> T as(String type, String role, UUID personId, Supplier<T> work) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("typ", type).claim("school_id", SCHOOL.toString());
        if (role != null) {
            jwt.claim("role", role);
        }
        if (personId != null) {
            jwt.claim("person_id", personId.toString());
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ResultActions saveScope(String roleId, String body) throws Exception {
        return mvc.perform(put(ROLES + "/" + roleId + "/scope").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode roleByCode(String code) throws Exception {
        for (JsonNode role : body(mvc.perform(get(ROLES).header("Authorization", superAdmin.bearer())))) {
            if (role.get("code").asText().equals(code)) {
                return role;
            }
        }
        throw new AssertionError("no role " + code);
    }

    /** The role code a caller from {@link TestRoles} holds (it is the token's role claim). */
    private String codeOf(Caller caller) throws Exception {
        return body(mvc.perform(get("/api/v1/admin/me").header("Authorization", caller.bearer()))).get("role").asText();
    }

    private UUID department(String name) throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/admin/departments").header("Authorization", superAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"" + name + " " + suffix() + "\"}}"))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asText());
        departments.add(id);
        return id;
    }

    private void add(String name, UUID department, String supervisor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into person (id, school_id, staff_id, name_km, name_en, role, department_id, supervisor_id,
                                            created_at, updated_at)
                        values (:id, :s, :staff, :n, :n, 'staff', :d, :sup, now(), now())""")
                .param("id", id).param("s", SCHOOL).param("staff", "RS-" + suffix()).param("n", name).param("d", department)
                .param("sup", supervisor == null ? null : person.get(supervisor)).update();
        person.put(name, id);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> names(List<Person> found) {
        return found.stream().map(p -> p.getName().getEn()).sorted().toList();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
