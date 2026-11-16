package kh.cmt.org;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kh.cmt.support.ApiTest;
import kh.cmt.support.TestJwt;
import kh.cmt.support.TestJwt.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The school's support contact: set in Settings › School, read by the app's login screen without signing in. */
@ApiTest
class SupportContactTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void theContactIsSetInSettingsAndReadWithoutSignIn() throws Exception {
        Caller admin = TestJwt.webUser("super_admin");
        JsonNode school = json.readTree(mvc.perform(get("/api/v1/admin/settings/school").header("Authorization", admin.bearer()))
                .andReturn().getResponse().getContentAsString());
        String body = "{\"name\":{\"en\":\"CMT School\"},\"workingDays\":[1,2,3,4,5,6],\"timezone\":\"Asia/Phnom_Penh\","
                + "\"supportPhone\":\"012 345 678\",\"supportEmail\":\"Admin@CMT.school\",\"version\":" + school.get("version") + "}";
        mvc.perform(put("/api/v1/admin/settings/school").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.supportEmail").value("admin@cmt.school"));

        mvc.perform(get("/api/v1/public/support"))   // no token
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("012 345 678"))
                .andExpect(jsonPath("$.email").value("admin@cmt.school"));

        mvc.perform(put("/api/v1/admin/settings/school").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("012 345 678", "call us").replace("\"version\":" + school.get("version"),
                        "\"version\":" + (school.get("version").asLong() + 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("supportPhone"));
        // only GET is public
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/public/support"))
                .andExpect(status().isUnauthorized());
    }
}
