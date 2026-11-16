package kh.cmt.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import kh.cmt.support.TestJwt.Caller;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Test helper: office users with a custom role that has exactly the permissions the test needs. */
public final class TestRoles {

    private TestRoles() {
    }

    /**
     * Makes sure a custom role with this English name and exactly these permissions exists (created as Super Admin),
     * and returns a caller who holds it.
     */
    public static Caller callerWithRole(MockMvc mvc, ObjectMapper json, String roleName,
            Map<String, List<String>> permissions) throws Exception {
        Caller superAdmin = TestJwt.webUser("super_admin");
        JsonNode existing = null;
        for (JsonNode role : json.readTree(mvc.perform(get("/api/v1/admin/roles").header("Authorization", superAdmin.bearer()))
                .andReturn().getResponse().getContentAsString())) {
            if (role.get("name").get("en").asText().equals(roleName)) {
                existing = role;
            }
        }
        if (existing == null) {
            existing = json.readTree(mvc.perform(post("/api/v1/admin/roles").header("Authorization", superAdmin.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":{\"en\":\"" + roleName + "\"}}"))
                    .andReturn().getResponse().getContentAsString());
        }
        existing = json.readTree(mvc.perform(put("/api/v1/admin/roles/" + existing.get("id").asText() + "/permissions")
                .header("Authorization", superAdmin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("permissions", permissions, "version", existing.get("version").asLong()))))
                .andReturn().getResponse().getContentAsString());
        return TestJwt.webUser(existing.get("code").asText());
    }
}
