package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionGuard;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.web.ApiException;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.support.TestRoles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Step 3.2 "done when": a Viewer gets 403 on any POST / PUT / DELETE. Also: permissions are read from the caller's
 * role, a change to a role applies to the very next request, and every admin endpoint has been given a decision.
 */
@ApiTest
@Import(PermissionsTest.GuardedProbe.class)
class PermissionsTest {

    private static final String ADMIN = "/api/v1/admin";
    private static final String[] KINDS = {"departments", "positions"};

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PermissionGuard guard;
    @Autowired GuardedProbe probe;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    final Caller superAdmin = TestJwt.webUser("super_admin");
    final UUID fakeId = UUID.randomUUID();

    /** One call a Viewer must not be able to make, and the permission it needs. */
    record Call(HttpMethod method, String template, String body, PermissionModule module, PermissionAction action) {
        String path(UUID id) {
            return template.replace("{id}", id.toString()).replace("{version}", "1").replace("{documentId}", id.toString())
                    .replace("{personId}", id.toString());
        }
    }

    /** Every endpoint that changes something, with a valid body (so a refusal can only come from the permission). */
    private List<Call> changingCalls() {
        List<Call> calls = new ArrayList<>();
        for (String kind : KINDS) {
            PermissionModule module = PermissionModule.PEOPLE;
            String name = "{\"name\":{\"en\":\"X1\"}}";
            String named = "{\"name\":{\"en\":\"X1\"},\"version\":0}";
            calls.add(new Call(HttpMethod.POST, ADMIN + "/" + kind, name, module, PermissionAction.CREATE));
            calls.add(new Call(HttpMethod.PUT, ADMIN + "/" + kind + "/{id}", named, module, PermissionAction.EDIT));
            calls.add(new Call(HttpMethod.DELETE, ADMIN + "/" + kind + "/{id}", null, module, PermissionAction.DELETE));
            calls.add(new Call(HttpMethod.POST, ADMIN + "/" + kind + "/{id}/restore", null, module, PermissionAction.EDIT));
            calls.add(new Call(HttpMethod.DELETE, ADMIN + "/" + kind + "/{id}/forever", null, module, PermissionAction.DELETE));
        }
        PermissionModule s = PermissionModule.SETTINGS;
        PermissionAction edit = PermissionAction.EDIT;
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/settings/school",
                "{\"name\":{\"en\":\"S\"},\"workingDays\":[1],\"timezone\":\"Asia/Phnom_Penh\",\"version\":0}", s, edit));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/settings/attendance",
                "{\"defaultGraceMinutes\":5,\"earlyCheckOutNeedsReason\":true,\"manualCheckInAllowed\":true,"
                        + "\"offlineCheckInAllowed\":true,\"missingCheckOutRule\":\"FLAG_ONLY\",\"version\":0}", s, edit));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/settings/notifications",
                "{\"checkInReminder\":true,\"checkInReminderMin\":10,\"checkOutReminder\":true,\"checkOutReminderMin\":0,"
                        + "\"shift2Reminder\":true,\"shift2ReminderMin\":10,\"version\":0}", s, edit));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/settings/appearance", "{\"defaultLanguage\":\"km\",\"defaultTheme\":\"system\",\"version\":0}", s, edit));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/settings/numbering",
                "{\"prefix\":\"CMT\",\"separator\":\"-\",\"length\":4,\"rolePrefix\":false,\"includeYear\":false,\"version\":0}", s, edit));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/settings/numbering/advance", "{\"nextNumber\":5}", s, edit));
        PermissionModule u = PermissionModule.USERS;
        calls.add(new Call(HttpMethod.POST, ADMIN + "/roles", "{\"name\":{\"en\":\"X1\"}}", u, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}", "{\"name\":{\"en\":\"X1\"},\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/permissions", "{\"permissions\":{},\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/scope", "{\"type\":\"ALL\",\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/sensitive-fields",
                "{\"salaryBank\":false,\"nationalId\":true,\"phone\":true,\"idScans\":false,\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/approval-limits",
                "{\"leaveMaxDays\":3,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"super_admin\",\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/login-rules",
                "{\"sessionTimeoutMinutes\":60,\"allowedDays\":[1,2,3,4,5],\"allowedIps\":[],\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/two-factor", "{\"required\":true,\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/roles/{id}", null, u, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/roles/{id}/force-logout", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/roles/{id}/history/{version}/restore", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/roles/{id}/default", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/web-users", "{\"name\":\"X\",\"username\":\"x.user\"}", u, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/web-users/{id}", "{\"name\":\"X\",\"username\":\"x.user\",\"version\":0}", u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/web-users/{id}", null, u, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/web-users/{id}/restore", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/web-users/{id}/reset-password", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/web-users/{id}/sign-out", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/web-users/{id}/reset-two-factor", null, u, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/web-users/move-role", "{\"userIds\":[\"" + fakeId + "\"],\"role\":\"viewer\"}", u, PermissionAction.EDIT));
        PermissionModule p = PermissionModule.PEOPLE;
        String person = "{\"name\":{\"km\":\"ក\",\"en\":\"X\"},\"role\":\"staff\"}";
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people", person, p, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/people/{id}", person.replace("}}", "},\"version\":0}"), p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people/{id}/inactivate", "{\"reason\":\"OTHER\"}", p, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people/{id}/restore", null, p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/people/{id}", null, p, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people/{id}/send-login", "{\"send\":\"none\"}", p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people/{id}/reset-password", "{\"send\":\"none\"}", p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people/{id}/documents", "{\"type\":\"CV\",\"fileId\":\"" + fakeId + "\"}", p,
                PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/people/{id}/documents/{documentId}", null, p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/people/{id}/pay", "{\"type\":\"none\",\"effectiveFrom\":\"2026-01-01\"}", p,
                PermissionAction.EDIT));
        PermissionModule sh = PermissionModule.SHIFTS;
        String template = "{\"name\":\"T\",\"shifts\":[{\"start\":\"07:00\",\"end\":\"12:00\"}]";
        calls.add(new Call(HttpMethod.POST, ADMIN + "/shift-templates", template + "}", sh, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/shift-templates/{id}", template + ",\"version\":0}", sh, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/shift-templates/{id}", null, sh, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/shift-templates/{id}/restore", null, sh, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/shift-templates/{id}/forever", null, sh, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/schedules/weekly", "{\"effectiveFrom\":\"2099-01-01\",\"days\":[]}", sh,
                PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/schedules/date-changes", "{\"dates\":[\"2099-01-01\"]}", sh, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/schedules/date-changes/{id}", null, sh, PermissionAction.DELETE));
        PermissionModule lv = PermissionModule.LEAVE;
        String holiday = "{\"name\":{\"en\":\"H\"},\"kind\":\"SCHOOL\",\"startDate\":\"2099-01-01\"";
        calls.add(new Call(HttpMethod.POST, ADMIN + "/holidays", holiday + "}", lv, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/holidays/{id}", holiday + ",\"version\":0}", lv, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/holidays/{id}", null, lv, PermissionAction.DELETE));
        String leaveType = "{\"name\":{\"en\":\"Study leave\"},\"daysPerYear\":5,\"color\":\"#1D4ED8\"";
        calls.add(new Call(HttpMethod.POST, ADMIN + "/leave-types", leaveType + "}", lv, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/leave-types/{id}", leaveType + ",\"version\":0}", lv, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/leave-types/{id}", null, lv, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/leave-types/{id}/restore", null, lv, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/leave-balances/{personId}/adjust", "{\"typeId\":\"" + fakeId + "\",\"days\":1,"
                + "\"reason\":\"Carried over\"}", lv, PermissionAction.EDIT));
        PermissionModule qr = PermissionModule.QR;
        String area = "{\"name\":\"A\",\"latitude\":11.5564,\"longitude\":104.9282,\"radiusMeters\":120";
        calls.add(new Call(HttpMethod.POST, ADMIN + "/areas", area + "}", qr, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/areas/{id}", area + ",\"version\":0}", qr, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/areas/{id}", null, qr, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/areas/{id}/restore", null, qr, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/areas/{id}/forever", null, qr, PermissionAction.DELETE));
        String gate = "{\"name\":\"G\",\"areaId\":\"" + fakeId + "\"";
        calls.add(new Call(HttpMethod.POST, ADMIN + "/gates", gate + "}", qr, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/gates/{id}", gate + ",\"version\":0}", qr, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/gates/{id}", null, qr, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/gates/{id}/restore", null, qr, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.DELETE, ADMIN + "/gates/{id}/forever", null, qr, PermissionAction.DELETE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/gates/{id}/qr", null, qr, PermissionAction.CREATE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/qr/{id}/expire", null, qr, PermissionAction.EDIT));
        PermissionModule ap = PermissionModule.APPROVALS;
        calls.add(new Call(HttpMethod.POST, ADMIN + "/approvals/{id}/approve", null, ap, PermissionAction.APPROVE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/approvals/{id}/reject", "{\"comment\":\"No\"}", ap, PermissionAction.APPROVE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/approvals/{id}/undo", null, ap, PermissionAction.APPROVE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/approvals/bulk-approve", "{\"ids\":[\"" + fakeId + "\"]}", ap, PermissionAction.APPROVE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/devices/requests/{id}/approve", null, ap, PermissionAction.APPROVE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/devices/requests/{id}/reject", "{\"reason\":\"No\"}", ap, PermissionAction.APPROVE));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/devices/{personId}/reset", "{\"reason\":\"Lost phone\"}", p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/devices/{personId}/block", "{\"reason\":\"Lost phone\"}", p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/devices/{personId}/unblock", null, p, PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/devices/settings", "{\"oneDevice\":true,\"changeRequests\":true,\"blockRooted\":true,"
                + "\"blockMockLocation\":true,\"version\":0}", p, PermissionAction.EDIT));
        PermissionModule at = PermissionModule.ATTENDANCE;
        calls.add(new Call(HttpMethod.PUT, ADMIN + "/attendance/records/{id}", "{\"checkIn\":\"07:40\",\"reason\":\"x\",\"version\":0}", at,
                PermissionAction.EDIT));
        calls.add(new Call(HttpMethod.POST, ADMIN + "/attendance/records", "{\"personId\":\"" + fakeId + "\",\"date\":\"2026-10-01\","
                + "\"checkIn\":\"07:40\",\"reason\":\"x\"}", at, PermissionAction.EDIT));
        return calls;
    }

    /** Read endpoints that need View in a module (the Viewer role has View everywhere except Users and Settings). */
    private static final Map<String, PermissionModule> GUARDED_READS = Map.ofEntries(
            Map.entry(ADMIN + "/settings", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/settings/school", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/settings/attendance", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/settings/notifications", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/settings/appearance", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/settings/numbering", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/settings/numbering/preview", PermissionModule.SETTINGS),
            Map.entry(ADMIN + "/roles", PermissionModule.USERS),
            Map.entry(ADMIN + "/roles/{id}", PermissionModule.USERS),
            Map.entry(ADMIN + "/roles/compare?a={id}&b={id}", PermissionModule.USERS),
            Map.entry(ADMIN + "/roles/{id}/users", PermissionModule.USERS),
            Map.entry(ADMIN + "/roles/{id}/history", PermissionModule.USERS),
            Map.entry(ADMIN + "/roles/{id}/view-as", PermissionModule.USERS),
            Map.entry(ADMIN + "/web-users", PermissionModule.USERS),
            Map.entry(ADMIN + "/web-users/{id}", PermissionModule.USERS),
            Map.entry(ADMIN + "/people/{id}/pay", PermissionModule.PEOPLE),    // People view + the salary flag
            Map.entry(ADMIN + "/people/import/template", PermissionModule.PEOPLE),   // People create
            Map.entry(ADMIN + "/gates/{id}/qr/print.pdf", PermissionModule.QR),     // QR create: a printed QR lets people check in
            Map.entry(ADMIN + "/gates/{id}/qr/print.png", PermissionModule.QR),
            Map.entry(ADMIN + "/attendance/export.xlsx", PermissionModule.ATTENDANCE));   // Attendance export

    // ------------------------------------------------------------------ the Viewer

    @Test
    void aViewerGets403OnEveryPostPutAndDelete_andTheErrorNamesWhatWasMissing() throws Exception {
        Caller viewer = TestJwt.webUser("viewer");
        List<Call> calls = changingCalls();
        assertThat(calls.stream().filter(c -> c.method() == HttpMethod.POST).count()).isGreaterThan(8);

        for (Call call : calls) {
            ResultActions result = mvc.perform(request(call.method(), call.path(fakeId)).header("Authorization", viewer.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(call.body() == null ? "" : call.body()));
            result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            JsonNode problem = json.readTree(result.andReturn().getResponse().getContentAsString());
            assertThat(problem.get("module").asText()).as("%s %s", call.method(), call.template()).isEqualTo(call.module().key());
            assertThat(problem.get("action").asText()).as("%s %s", call.method(), call.template()).isEqualTo(call.action().key());
        }
    }

    @Test
    void aViewerCanReadWhatItHasViewOnButNotUsersOrSettings() throws Exception {
        Caller viewer = TestJwt.webUser("viewer");
        // active lists (dropdowns) are open to every office user; the Inactive tab needs View in the module
        for (String kind : KINDS) {
            mvc.perform(get(ADMIN + "/" + kind).header("Authorization", viewer.bearer())).andExpect(status().isOk());
            mvc.perform(get(ADMIN + "/" + kind).param("status", "inactive").header("Authorization", viewer.bearer()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(ADMIN + "/me").header("Authorization", viewer.bearer())).andExpect(status().isOk());

        // the Viewer role has no Users and no Settings access: those reads are refused
        for (var read : GUARDED_READS.entrySet()) {
            String path = read.getKey().replace("{id}", fakeId.toString());
            mvc.perform(get(path).header("Authorization", viewer.bearer())).andExpect(status().isForbidden());
        }
    }

    @Test
    void eachRoleSeesOnlyItsOwnModules() throws Exception {
        Caller hr = TestJwt.webUser("hr");               // People yes, Settings / Users no
        Caller qr = TestJwt.webUser("qr_manager");       // neither

        create(hr, "/departments", "{\"name\":{\"en\":\"" + unique("HR dept") + "\"}}").andExpect(status().isCreated());
        create(hr, "/positions", "{\"name\":{\"en\":\"" + unique("HR pos") + "\"}}").andExpect(status().isCreated());
        mvc.perform(get(ADMIN + "/settings").header("Authorization", hr.bearer())).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN + "/roles").header("Authorization", hr.bearer())).andExpect(status().isForbidden());

        create(qr, "/departments", "{\"name\":{\"en\":\"" + unique("QR dept") + "\"}}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.module").value("people"));

        // Super Admin may do all of it
        create(superAdmin, "/departments", "{\"name\":{\"en\":\"" + unique("SA dept") + "\"}}").andExpect(status().isCreated());
        mvc.perform(get(ADMIN + "/settings").header("Authorization", superAdmin.bearer())).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ changes apply to the next request

    @Test
    void aChangeToARoleAppliesToTheVeryNextRequestWithTheSameToken() throws Exception {
        Caller clerk = TestRoles.callerWithRole(mvc, json, unique("Clerk"), Map.of("people", List.of("view", "create")));
        String code = codeOf(clerk);
        String roleId = roleId(code);

        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Clerk dept") + "\"}}").andExpect(status().isCreated());
        long versionBefore = roleVersion(code);

        // Super Admin takes Create away (the clerk's token is the same one, issued before the change)
        savePermissions(roleId, "{\"people\":[\"view\"]}");
        assertThat(permVersion(code)).isEqualTo(versionBefore + 1);
        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Clerk dept") + "\"}}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.action").value("create"));

        // and gives it back
        savePermissions(roleId, "{\"people\":[\"view\",\"create\"]}");
        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Clerk dept") + "\"}}").andExpect(status().isCreated());
    }

    @Test
    void permissionsAreCachedByRoleAndVersion_andTheCacheIsClearedByChangesOnly() throws Exception {
        Caller clerk = TestRoles.callerWithRole(mvc, json, unique("Cached"), Map.of("people", List.of("view", "create")));
        String code = codeOf(clerk);
        String versionKey = "perm:ver:" + TestJwt.SCHOOL + ":" + code;

        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Cache dept") + "\"}}").andExpect(status().isCreated());
        String version = redis.opsForValue().get(versionKey);
        assertThat(version).isEqualTo(String.valueOf(permVersion(code)));
        assertThat(redis.opsForValue().get("perm:" + TestJwt.SCHOOL + ":" + code + ":" + version)).contains("people:create");

        // the database is changed behind the application's back: requests are still served from the cache...
        jdbc.update("delete from role_permission where role_id = ?", UUID.fromString(roleId(code)));
        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Cache dept") + "\"}}").andExpect(status().isCreated());

        // ...because the cache is keyed by permission version. A real change always raises the version (and clears
        // the version key after the commit); doing the same by hand makes the database be read again.
        jdbc.update("update role set perm_version = perm_version + 1 where school_id = ? and code = ?", TestJwt.SCHOOL, code);
        redis.delete(versionKey);
        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Cache dept") + "\"}}").andExpect(status().isForbidden());
        assertThat(redis.opsForValue().get("perm:" + TestJwt.SCHOOL + ":" + code + ":" + redis.opsForValue().get(versionKey)))
                .isEqualTo("-");   // a role that grants nothing is cached too
    }

    @Test
    void aDeletedRoleOrAnUnknownRoleGrantsNothing() throws Exception {
        Caller clerk = TestRoles.callerWithRole(mvc, json, unique("Doomed"), Map.of("people", List.of("view", "create")));
        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Doomed dept") + "\"}}").andExpect(status().isCreated());

        mvc.perform(delete(ADMIN + "/roles/" + roleId(codeOf(clerk))).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNoContent());
        create(clerk, "/departments", "{\"name\":{\"en\":\"" + unique("Doomed dept") + "\"}}").andExpect(status().isForbidden());

        create(TestJwt.webUser("ghost_role"), "/departments", "{\"name\":{\"en\":\"" + unique("Ghost") + "\"}}")
                .andExpect(status().isForbidden());
    }

    @Test
    void aSchoolThatNeverOpenedTheRolesPageStillHasItsSystemRoles() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.update("insert into school (id, code, name_en, created_at, updated_at) values (?, ?, 'Other', now(), now())",
                other, "T" + other.toString().substring(0, 8));
        try {
            // the very first request of this school is a guarded one
            mvc.perform(get(ADMIN + "/settings").header("Authorization", TestJwt.webUser("super_admin", other).bearer()))
                    .andExpect(status().isOk());
            mvc.perform(get(ADMIN + "/settings").header("Authorization", TestJwt.webUser("viewer", other).bearer()))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.update("delete from role where school_id = ?", other);
            jdbc.update("delete from school_settings where school_id = ?", other);
            jdbc.update("delete from school where id = ?", other);
        }
    }

    // ------------------------------------------------------------------ the admin app and other callers

    @Test
    void appAdminsGetAFixedSetAndEveryoneElseGetsNothing() {
        UUID school = TestJwt.SCHOOL;
        CurrentUser appAdmin = new CurrentUser(UUID.randomUUID(), "app", null, null, "admin", 1, null, school, "km", List.of("pwd"));
        CurrentUser appStaff = new CurrentUser(UUID.randomUUID(), "app", null, null, "staff", 1, null, school, "km", List.of("pwd"));
        CurrentUser appTeacher = new CurrentUser(UUID.randomUUID(), "app", null, null, "teacher", 1, null, school, "km", List.of("pwd"));
        CurrentUser job = new CurrentUser(UUID.randomUUID(), "job", null, null, null, 0, null, school, null, List.of());

        assertThat(guard.allows(appAdmin, PermissionModule.APPROVALS, PermissionAction.VIEW)).isTrue();
        assertThat(guard.allows(appAdmin, PermissionModule.APPROVALS, PermissionAction.APPROVE)).isTrue();
        assertThat(guard.allows(appAdmin, PermissionModule.ANNOUNCEMENTS, PermissionAction.CREATE)).isTrue();
        assertThat(guard.allows(appAdmin, PermissionModule.PEOPLE, PermissionAction.CREATE)).isFalse();
        assertThat(guard.allows(appAdmin, PermissionModule.SETTINGS, PermissionAction.VIEW)).isFalse();
        assertThat(guard.allows(appAdmin, PermissionModule.USERS, PermissionAction.VIEW)).isFalse();
        for (CurrentUser nobody : List.of(appStaff, appTeacher, job)) {
            for (PermissionModule module : PermissionModule.values()) {
                assertThat(guard.allows(nobody, module, PermissionAction.VIEW)).isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ @RequirePermission outside HTTP

    @Test
    void theAnnotationGuardsAnyUseCaseCallNotOnlyWebRequests() {
        try {
            signIn("web", "hr");
            assertThat(probe.edit()).isEqualTo("edited");            // HR may edit People
            assertThat(probe.exportReport()).isEqualTo("exported");  // class-level: Reports export
            assertThatThrownBy(probe::viewLog).isInstanceOf(ApiException.class);   // method-level wins: Log view, HR has none

            signIn("web", "viewer");
            assertThatThrownBy(probe::edit).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).properties()).containsEntry("module", "people").containsEntry("action", "edit"));
            assertThat(probe.viewLog()).isEqualTo("log");
            assertThatThrownBy(probe::exportReport).isInstanceOf(ApiException.class);

            signIn("job", null);
            assertThatThrownBy(probe::viewLog).isInstanceOf(ApiException.class);

            SecurityContextHolder.clearContext();
            assertThatThrownBy(probe::edit).isInstanceOf(ApiException.class);   // nobody signed in
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ nothing is forgotten

    @Test
    void everyAdminEndpointHasBeenGivenAPermissionDecision() {
        Set<String> decided = new LinkedHashSet<>();
        // the five lists share one mapping: /api/v1/admin/{kind}
        changingCalls().forEach(c -> decided.add(c.method() + " "
                + c.template().replaceAll("/(departments|positions)(/|$)", "/{kind}$2")));
        GUARDED_READS.keySet().forEach(p -> decided.add("GET " + p.replaceAll("\\?.*", "")));
        // open on purpose: dropdown lists (the Inactive tab is guarded inside), single items, and "who am I"
        decided.add("GET " + ADMIN + "/{kind}");
        decided.add("GET " + ADMIN + "/{kind}/{id}");
        decided.add("GET " + ADMIN + "/me");
        // People view (the Viewer role has it, so they are not in GUARDED_READS); covered by PeopleTest
        decided.add("GET " + ADMIN + "/people");
        decided.add("GET " + ADMIN + "/people/{id}");
        decided.add("GET " + ADMIN + "/people/{id}/documents");
        // Shifts view (the Viewer role has it); covered by ShiftTemplatesTest
        decided.add("GET " + ADMIN + "/shift-templates");
        decided.add("GET " + ADMIN + "/shift-templates/{id}");
        decided.add("GET " + ADMIN + "/schedules/weekly");
        decided.add("GET " + ADMIN + "/schedules/weekly/preview");
        // Shifts view / Leave & holidays view (the Viewer role has both); covered by DateChangesHolidaysTest
        decided.add("GET " + ADMIN + "/schedules/date-changes");
        decided.add("GET " + ADMIN + "/holidays");
        decided.add("GET " + ADMIN + "/holidays/{id}");
        // QR view (the Viewer role has it); covered by AreasGatesTest
        decided.add("GET " + ADMIN + "/areas");
        decided.add("GET " + ADMIN + "/areas/{id}");
        decided.add("GET " + ADMIN + "/gates");
        decided.add("GET " + ADMIN + "/gates/{id}");
        decided.add("GET " + ADMIN + "/qr/history");
        // People view (the Viewer role has it); covered by DevicesTest
        decided.add("GET " + ADMIN + "/devices");
        decided.add("GET " + ADMIN + "/devices/requests");
        decided.add("GET " + ADMIN + "/devices/history");
        decided.add("GET " + ADMIN + "/devices/settings");
        // Attendance view (the Viewer role has it); covered by AdminAttendanceTest
        decided.add("GET " + ADMIN + "/attendance/day");
        decided.add("GET " + ADMIN + "/attendance/month");
        // Approvals view (the Viewer role has it); covered by LeaveRequestsTest
        decided.add("GET " + ADMIN + "/approvals");
        decided.add("GET " + ADMIN + "/approvals/{id}");
        // Leave view (the Viewer role has it); covered by LeaveBalancesTest
        decided.add("GET " + ADMIN + "/leave-types");
        decided.add("GET " + ADMIN + "/leave-balances");
        decided.add("GET " + ADMIN + "/leave-balances/{personId}");
        // People create; multipart uploads, so the permission refusal is tested in PeopleImportTest
        decided.add("POST " + ADMIN + "/people/import/preview");
        decided.add("POST " + ADMIN + "/people/import/commit");

        Set<String> existing = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            if (info.getPathPatternsCondition() == null) {
                return;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (pattern.startsWith(ADMIN + "/")) {
                    info.getMethodsCondition().getMethods().forEach(m ->
                            existing.add(m + " " + pattern.replaceAll("\\{(\\w+):[^}]*}", "{$1}")));
                }
            }
        });

        assertThat(existing).as("an admin endpoint exists that no permission decision was recorded for: add a @RequirePermission "
                + "(or a guard call) and list it in this test").isSubsetOf(decided);
        assertThat(decided).as("this test lists an endpoint that does not exist any more").isSubsetOf(existing);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder request(HttpMethod method, String path) {
        return switch (method.name()) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
    }

    private ResultActions create(Caller caller, String path, String body) throws Exception {
        return mvc.perform(post(ADMIN + path).header("Authorization", caller.bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void savePermissions(String roleId, String permissionsJson) throws Exception {
        long version = json.readTree(mvc.perform(get(ADMIN + "/roles/" + roleId).header("Authorization", superAdmin.bearer()))
                .andReturn().getResponse().getContentAsString()).get("version").asLong();
        mvc.perform(put(ADMIN + "/roles/" + roleId + "/permissions").header("Authorization", superAdmin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":" + permissionsJson + ",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private String roleId(String code) throws Exception {
        for (JsonNode role : json.readTree(mvc.perform(get(ADMIN + "/roles").header("Authorization", superAdmin.bearer()))
                .andReturn().getResponse().getContentAsString())) {
            if (role.get("code").asText().equals(code)) {
                return role.get("id").asText();
            }
        }
        throw new AssertionError("no role " + code);
    }

    private long roleVersion(String code) {
        return permVersion(code);
    }

    private int permVersion(String code) {
        return jdbc.queryForObject("select perm_version from role where school_id = ? and code = ?", Integer.class, TestJwt.SCHOOL, code);
    }

    /** The role code inside a caller token (the second part of the JWT). */
    private String codeOf(Caller caller) throws Exception {
        String payload = caller.bearer().split("\\.")[1];
        return json.readTree(java.util.Base64.getUrlDecoder().decode(payload)).get("role").asText();
    }

    private void signIn(String type, String role) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("typ", type).claim("school_id", TestJwt.SCHOOL.toString());
        if (role != null) {
            jwt.claim("role", role);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A use case outside the web layer, guarded with the annotation. */
    @Service
    @RequirePermission(module = PermissionModule.REPORTS, action = PermissionAction.EXPORT)
    static class GuardedProbe {

        @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
        public String edit() {
            return "edited";
        }

        @RequirePermission(module = PermissionModule.LOG, action = PermissionAction.VIEW)
        public String viewLog() {
            return "log";
        }

        public String exportReport() {   // no annotation of its own: the class-level one applies
            return "exported";
        }
    }
}
