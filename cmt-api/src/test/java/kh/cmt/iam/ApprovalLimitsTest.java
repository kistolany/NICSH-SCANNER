package kh.cmt.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.iam.api.ApprovalRequest;
import kh.cmt.iam.api.ApprovalRouting;
import kh.cmt.iam.api.ApprovalRouting.Decision;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import kh.cmt.support.TestRoles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Step 3.5 "done when": a request above the limit moves to the escalation role's inbox.
 * <p>
 * The approvals inbox itself comes with the approvals module (phase 8); it asks {@link ApprovalRouting} where a
 * request goes. The limits are set through the real API.
 */
@ApiTest
class ApprovalLimitsTest {

    private static final String ROLES = "/api/v1/admin/roles";
    private static final UUID SCHOOL = TestJwt.SCHOOL;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApprovalRouting routing;

    @Autowired
    Clock clock;

    private final Caller superAdmin = TestJwt.webUser("super_admin");

    // ------------------------------------------------------------------ the "done when" check

    @Test
    void aRequestAboveTheLimitMovesToTheEscalationRolesInbox() throws Exception {
        // Head of department: leave up to 2 days, then HR (system role: up to 3 days, then Super Admin)
        String head = role("Head of department", "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"hr\"}");
        UUID someone = UUID.randomUUID();

        assertThat(routing.inboxFor(SCHOOL, head, ApprovalRequest.leave(1, someone))).isEqualTo(head);
        assertThat(routing.inboxFor(SCHOOL, head, ApprovalRequest.leave(2, someone))).isEqualTo(head);
        assertThat(routing.inboxFor(SCHOOL, head, ApprovalRequest.leave(3, someone))).isEqualTo("hr");
        assertThat(routing.inboxFor(SCHOOL, head, ApprovalRequest.leave(5, someone))).isEqualTo("super_admin");

        // the head opens a 3-day request: not theirs to approve, it moves to HR
        Decision decision = routing.check(SCHOOL, head, UUID.randomUUID(), ApprovalRequest.leave(3, someone));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.escalateTo()).isEqualTo("hr");
        assertThat(decision.reason()).isEqualTo(Decision.Reason.LEAVE_TOO_LONG);

        // a 5-day request skips HR too (HR could not approve it either)
        assertThat(routing.check(SCHOOL, head, null, ApprovalRequest.leave(5, someone)).escalateTo()).isEqualTo("super_admin");
        assertThat(routing.check(SCHOOL, "hr", null, ApprovalRequest.leave(5, someone)).escalateTo()).isEqualTo("super_admin");
        assertThat(routing.check(SCHOOL, "hr", null, ApprovalRequest.leave(3, someone)).allowed()).isTrue();
        assertThat(routing.check(SCHOOL, "super_admin", null, ApprovalRequest.leave(60, someone)).allowed()).isTrue();
    }

    @Test
    void oldCorrectionsAndOwnRequests() throws Exception {
        String head = role("Head of department", "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"hr\"}");
        LocalDate today = LocalDate.now(clock);
        UUID someone = UUID.randomUUID();

        assertThat(routing.check(SCHOOL, head, null, ApprovalRequest.correction(today.minusDays(7), someone)).allowed()).isTrue();
        Decision old = routing.check(SCHOOL, head, null, ApprovalRequest.correction(today.minusDays(8), someone));
        assertThat(old.reason()).isEqualTo(Decision.Reason.CORRECTION_TOO_OLD);
        assertThat(old.escalateTo()).isEqualTo("super_admin");   // HR may not approve old corrections either
        assertThat(routing.check(SCHOOL, "super_admin", null, ApprovalRequest.correction(today.minusDays(40), someone)).allowed()).isTrue();

        // own request: the head's own leave goes to HR, even a short one
        UUID headPerson = UUID.randomUUID();
        Decision own = routing.check(SCHOOL, head, headPerson, ApprovalRequest.leave(1, headPerson));
        assertThat(own.reason()).isEqualTo(Decision.Reason.OWN_REQUEST);
        assertThat(own.escalateTo()).isEqualTo("hr");
        // a Super Admin's own request needs another Super Admin
        UUID boss = UUID.randomUUID();
        Decision bossOwn = routing.check(SCHOOL, "super_admin", boss, ApprovalRequest.leave(1, boss));
        assertThat(bossOwn.allowed()).isFalse();
        assertThat(bossOwn.escalateTo()).isEqualTo("super_admin");

        // switched on: the head may approve old corrections and their own requests
        role("Head of department", "{\"leaveMaxDays\":2,\"oldCorrections\":true,\"ownRequests\":true,\"escalateTo\":\"hr\"}");
        assertThat(routing.check(SCHOOL, head, null, ApprovalRequest.correction(today.minusDays(30), someone)).allowed()).isTrue();
        assertThat(routing.check(SCHOOL, head, headPerson, ApprovalRequest.leave(1, headPerson)).allowed()).isTrue();
        assertThat(routing.check(SCHOOL, head, headPerson, ApprovalRequest.leave(3, headPerson)).escalateTo()).isEqualTo("hr");
    }

    @Test
    void everyChainEndsAtSuperAdmin() throws Exception {
        // escalation role deleted later: requests above the limit go to Super Admin
        String deputy = role("Deputy " + suffix(), "{\"leaveMaxDays\":5,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"super_admin\"}");
        String clerk = role("Clerk " + suffix(), "{\"leaveMaxDays\":1,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"" + deputy + "\"}");
        assertThat(routing.inboxFor(SCHOOL, clerk, ApprovalRequest.leave(4, null))).isEqualTo(deputy);
        mvc.perform(delete(ROLES + "/" + roleByCode(deputy).get("id").asText()).header("Authorization", superAdmin.bearer()))
                .andExpect(status().isNoContent());
        assertThat(routing.inboxFor(SCHOOL, clerk, ApprovalRequest.leave(4, null))).isEqualTo("super_admin");
        assertThat(routing.check(SCHOOL, clerk, null, ApprovalRequest.leave(4, null)).escalateTo()).isEqualTo("super_admin");

        // a role that does not exist approves nothing
        assertThat(routing.check(SCHOOL, "no_such_role", null, ApprovalRequest.other(null)).allowed()).isFalse();
        // other requests have no limits
        assertThat(routing.check(SCHOOL, clerk, null, ApprovalRequest.other(UUID.randomUUID())).allowed()).isTrue();
    }

    // ------------------------------------------------------------------ the limits on the Roles page

    @Test
    void theLimitsAreShownOnTheRoles() throws Exception {
        mvc.perform(get(ROLES).header("Authorization", superAdmin.bearer()))
                .andExpect(jsonPath("$[?(@.code == 'super_admin')].approvalLimits.oldCorrections").value(true))
                .andExpect(jsonPath("$[?(@.code == 'hr')].approvalLimits.leaveMaxDays").value(3))
                .andExpect(jsonPath("$[?(@.code == 'hr')].approvalLimits.escalateTo").value("super_admin"))
                .andExpect(jsonPath("$[?(@.code == 'hr')].approvalLimits.ownRequests").value(false));
    }

    @Test
    void wrongLimitsAreRefused() throws Exception {
        String a = role("Loop A " + suffix(), "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"super_admin\"}");
        String b = role("Loop B " + suffix(), "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"" + a + "\"}");
        JsonNode roleA = roleByCode(a);
        String id = roleA.get("id").asText();
        long version = roleA.get("version").asLong();

        save(id, "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"" + b + "\",\"version\":" + version + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rule").value("Loop"));
        save(id, "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"" + a + "\",\"version\":" + version + "}")
                .andExpect(jsonPath("$.errors[0].rule").value("Self"));
        save(id, "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false,\"escalateTo\":\"no_such\",\"version\":" + version + "}")
                .andExpect(jsonPath("$.errors[0].rule").value("UnknownRole"));
        save(id, "{\"leaveMaxDays\":0,\"escalateTo\":\"hr\",\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("approvalLimits.leaveMaxDays"))
                .andExpect(jsonPath("$.errors[1].field").value("approvalLimits.oldCorrections"))
                .andExpect(jsonPath("$.errors[2].field").value("approvalLimits.ownRequests"));
        save(id, "{\"leaveMaxDays\":2,\"oldCorrections\":false,\"ownRequests\":false}").andExpect(status().isBadRequest());   // no version

        save(roleByCode("hr").get("id").asText(), "{\"leaveMaxDays\":30,\"oldCorrections\":true,\"ownRequests\":true,\"version\":0}")
                .andExpect(jsonPath("$.code").value("ROLE_LOCKED"));
        mvc.perform(put(ROLES + "/" + id + "/approval-limits").header("Authorization", TestJwt.webUser("viewer").bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leaveMaxDays\":2,\"oldCorrections\":true,\"ownRequests\":true,\"version\":" + version + "}"))
                .andExpect(status().isForbidden());

        // the holder of a role cannot raise their own limits
        TestRoles.callerWithRole(mvc, json, roleA.get("name").get("en").asText(),
                Map.of("leave", List.of("view", "approve"), "users", List.of("view", "edit")));
        JsonNode now = roleByCode(a);
        mvc.perform(put(ROLES + "/" + id + "/approval-limits").header("Authorization", TestJwt.webUser(a).bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveMaxDays\":30,\"oldCorrections\":true,\"ownRequests\":true,\"version\":" + now.get("version") + "}"))
                .andExpect(jsonPath("$.code").value("OWN_ROLE"));
    }

    // ------------------------------------------------------------------ helpers

    /** A custom role that approves leave, with these limits (created or updated). Returns its code. */
    private String role(String name, String limits) throws Exception {
        Caller holder = TestRoles.callerWithRole(mvc, json, name, Map.of("leave", List.of("view", "approve"), "approvals", List.of("view", "approve")));
        String code = body(mvc.perform(get("/api/v1/admin/me").header("Authorization", holder.bearer()))).get("role").asText();
        JsonNode role = roleByCode(code);
        save(role.get("id").asText(), limits.substring(0, limits.length() - 1) + ",\"version\":" + role.get("version") + "}")
                .andExpect(status().isOk());
        return code;
    }

    private ResultActions save(String roleId, String body) throws Exception {
        return mvc.perform(put(ROLES + "/" + roleId + "/approval-limits").header("Authorization", superAdmin.bearer())
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

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
