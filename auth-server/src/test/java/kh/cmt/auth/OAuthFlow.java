package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/** Test helper: the mobile app's login (Authorization Code + PKCE) and refresh calls, step by step or in one go. */
class OAuthFlow {

    static final String REDIRECT = "https://oauth.pstmn.io/v1/callback";

    /** One login attempt in progress: its browser session, PKCE verifier and authorize parameters. */
    record Attempt(MockHttpSession session, String verifier, LinkedMultiValueMap<String, String> params) {
    }

    private final MockMvc mvc;
    private final ObjectMapper json;

    OAuthFlow(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    Attempt begin() throws Exception {
        String verifier = verifier();
        var params = new LinkedMultiValueMap<String, String>();
        params.add("response_type", "code");
        params.add("client_id", "cmt-mobile");
        params.add("scope", "openid profile");
        params.add("redirect_uri", REDIRECT);
        params.add("state", "xyz");
        params.add("code_challenge", challenge(verifier));
        params.add("code_challenge_method", "S256");
        Attempt attempt = new Attempt(new MockHttpSession(), verifier, params);
        authorize(attempt);   // not signed in yet -> redirected to /login, request remembered in the session
        return attempt;
    }

    MvcResult authorize(Attempt attempt) throws Exception {
        return mvc.perform(get("/oauth2/authorize").session(attempt.session()).queryParams(attempt.params()))
                .andReturn();
    }

    MvcResult submitLogin(Attempt attempt, String username, String password) throws Exception {
        return mvc.perform(post("/login").session(attempt.session()).with(csrf())
                .param("username", username).param("password", password)).andReturn();
    }

    /** Exchanges the code found in a redirect URL for tokens. */
    JsonNode exchange(Attempt attempt, String redirectUrl) throws Exception {
        assertThat(redirectUrl).startsWith(REDIRECT);
        String code = UriComponentsBuilder.fromUri(URI.create(redirectUrl)).build().getQueryParams().getFirst("code");
        MvcResult token = mvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", REDIRECT).param("client_id", "cmt-mobile")
                .param("code_verifier", attempt.verifier())).andReturn();
        assertThat(token.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(token.getResponse().getContentAsString());
    }

    /** Full successful login; returns the token response. */
    JsonNode login(String username, String password) throws Exception {
        Attempt attempt = begin();
        submitLogin(attempt, username, password);
        String location = authorize(attempt).getResponse().getRedirectedUrl();
        return exchange(attempt, location);
    }

    MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/oauth2/token")
                .param("grant_type", "refresh_token").param("refresh_token", refreshToken)
                .param("client_id", "cmt-mobile")).andReturn();
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String verifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
