package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import kh.cmt.auth.support.CapturingSmsSender;
import kh.cmt.auth.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Step 1.7 "done when" (auth server side): login-type endpoints answer 429 after the limit, per IP. */
// the real default is 10 per minute; this server's own sign-in pages (as in AuthServerTest)
@SpringBootTest(properties = {"auth.rate-limit.login.capacity=3", "auth.web.login-url=", "auth.web.allowed-origins="})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, CapturingSmsSender.Config.class})
class RateLimitTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void passwordLoginAnswers429AfterTheLimitPerIp() throws Exception {
        for (int i = 1; i <= 3; i++) {
            // a wrong password redirects back to the login page
            assertThat(login("198.51.100.1", "wrong-password").getResponse().getStatus()).as("attempt %d", i).isEqualTo(302);
        }
        MvcResult blocked = login("198.51.100.1", "wrong-password");

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).get("code").asText()).isEqualTo("RATE_LIMITED");

        // another address is not affected
        assertThat(login("198.51.100.2", "wrong-password").getResponse().getStatus()).isEqualTo(302);
    }

    @Test
    void theLimitAppliesEvenWhenThePasswordWouldBeRight() throws Exception {
        for (int i = 1; i <= 3; i++) {
            login("198.51.100.3", "wrong-password");
        }
        assertThat(login("198.51.100.3", "Test@12345").getResponse().getStatus()).isEqualTo(429);
    }


    @Test
    void secondStepCodesAreLimitedToo() throws Exception {
        int limited = 0;
        for (int i = 1; i <= 5; i++) {
            MvcResult result = mvc.perform(post("/login/otp").with(csrf()).with(from("198.51.100.5"))
                    .param("code", "000000")).andReturn();
            if (result.getResponse().getStatus() == 429) {
                limited++;
            }
        }
        assertThat(limited).isEqualTo(2);   // 3 get through (and fail for other reasons: nobody is signed in), 2 are blocked
    }

    @Test
    void pagesAreNotCounted() throws Exception {
        for (int i = 1; i <= 10; i++) {
            assertThat(mvc.perform(get("/login").with(from("198.51.100.6"))).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    private MvcResult login(String ip, String password) throws Exception {
        return mvc.perform(post("/login").with(csrf()).with(from(ip))
                .param("username", "test.staff").param("password", password)).andReturn();
    }


    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
