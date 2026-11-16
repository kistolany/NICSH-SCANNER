package kh.cmt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import kh.cmt.auth.keys.SigningKeyService;
import kh.cmt.auth.support.AuthServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/** Step 1.1 "done when": get a token with Authorization Code + PKCE for a test user. */
@AuthServerTest
class AuthorizationCodePkceFlowTest {

    static final String REDIRECT = "https://oauth.pstmn.io/v1/callback";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SigningKeyService keys;

    @Test
    void mobileClientGetsTokensWithPkce() throws Exception {
        String verifier = randomVerifier();
        MockHttpSession session = new MockHttpSession();

        JsonNode tokens = loginAndExchange(session, verifier, "test.staff", "Test@12345");

        assertThat(tokens.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expires_in").asLong()).isBetween(890L, 900L);   // 15 minutes
        assertThat(tokens.hasNonNull("refresh_token")).isTrue();
        assertThat(tokens.hasNonNull("id_token")).isTrue();

        SignedJWT access = SignedJWT.parse(tokens.get("access_token").asText());
        assertThat(access.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(access.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:9200");
        assertThat(UUID.fromString(access.getJWTClaimsSet().getSubject())).isNotNull();   // account id (see TokenClaimsTest)
        assertThat(verifiesAgainstJwks(access)).isTrue();
    }

    @Test
    void wrongPasswordDoesNotLogIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/oauth2/authorize").session(session).queryParams(authorizeParams(challenge(randomVerifier()))));
        MvcResult login = mvc.perform(post("/login").session(session).with(csrf())
                .param("username", "test.staff").param("password", "wrong")).andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).contains("/login?error");
    }

    @Test
    void mobileClientWithoutPkceIsRefused() throws Exception {
        var params = authorizeParams(null);
        params.remove("code_challenge");
        params.remove("code_challenge_method");
        MvcResult result = mvc.perform(get("/oauth2/authorize").queryParams(params)).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).contains("error=invalid_request");
    }

    @Test
    void refreshTokenWorksWithoutSecretAndRotates() throws Exception {
        JsonNode first = loginAndExchange(new MockHttpSession(), randomVerifier(), "test.staff", "Test@12345");
        String refresh1 = first.get("refresh_token").asText();

        MvcResult second = mvc.perform(post("/oauth2/token")
                .param("grant_type", "refresh_token").param("refresh_token", refresh1)
                .param("client_id", "cmt-mobile")).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String refresh2 = json.readTree(second.getResponse().getContentAsString()).get("refresh_token").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);   // rotated

        // the old refresh token is dead
        MvcResult replay = mvc.perform(post("/oauth2/token")
                .param("grant_type", "refresh_token").param("refresh_token", refresh1)
                .param("client_id", "cmt-mobile")).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void wrongCodeVerifierIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var params = authorizeParams(challenge(randomVerifier()));
        mvc.perform(get("/oauth2/authorize").session(session).queryParams(params));
        mvc.perform(post("/login").session(session).with(csrf())
                .param("username", "test.staff").param("password", "Test@12345"));
        String location = mvc.perform(get("/oauth2/authorize").session(session).queryParams(params))
                .andReturn().getResponse().getRedirectedUrl();
        String code = UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams().getFirst("code");

        MvcResult token = mvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", REDIRECT).param("client_id", "cmt-mobile")
                .param("code_verifier", randomVerifier())).andReturn();
        assertThat(token.getResponse().getStatus()).isEqualTo(400);
        assertThat(token.getResponse().getContentAsString()).contains("invalid_grant");
    }

    @Test
    void jobsClientGetsTokenWithClientCredentials() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("cmt-jobs", "dev-jobs-secret"))
                        .param("grant_type", "client_credentials").param("scope", "jobs"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("scope").asText()).isEqualTo("jobs");
    }

    @Test
    void rotatingTheSigningKeyKeepsOldTokensValid() throws Exception {
        JsonNode before = loginAndExchange(new MockHttpSession(), randomVerifier(), "test.staff", "Test@12345");
        SignedJWT oldToken = SignedJWT.parse(before.get("access_token").asText());
        String oldKid = oldToken.getHeader().getKeyID();

        String newKid = keys.rotate();

        JsonNode after = loginAndExchange(new MockHttpSession(), randomVerifier(), "test.staff", "Test@12345");
        SignedJWT newToken = SignedJWT.parse(after.get("access_token").asText());

        assertThat(newKid).isNotEqualTo(oldKid);
        assertThat(newToken.getHeader().getKeyID()).isEqualTo(newKid);
        assertThat(verifiesAgainstJwks(oldToken)).isTrue();    // retired key still published
        assertThat(verifiesAgainstJwks(newToken)).isTrue();

        JWKSet published = jwks();
        assertThat(published.getKeys()).extracting(k -> k.getKeyID()).contains(oldKid, newKid);
        assertThat(published.getKeys()).noneMatch(k -> k.isPrivate());   // JWKS never exposes private keys
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode loginAndExchange(MockHttpSession session, String verifier, String user, String password)
            throws Exception {
        var params = authorizeParams(challenge(verifier));

        MvcResult toLogin = mvc.perform(get("/oauth2/authorize").session(session).queryParams(params)).andReturn();
        assertThat(toLogin.getResponse().getRedirectedUrl()).endsWith("/login");

        MvcResult loggedIn = mvc.perform(post("/login").session(session).with(csrf())
                .param("username", user).param("password", password)).andReturn();
        assertThat(loggedIn.getResponse().getRedirectedUrl()).contains("/oauth2/authorize");

        MvcResult authorized = mvc.perform(get("/oauth2/authorize").session(session).queryParams(params)).andReturn();
        String location = authorized.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT);
        var back = UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams();
        assertThat(back.getFirst("state")).isEqualTo("xyz");
        String code = back.getFirst("code");
        assertThat(code).isNotBlank();

        MvcResult token = mvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT)
                .param("client_id", "cmt-mobile")
                .param("code_verifier", verifier)).andReturn();
        assertThat(token.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(token.getResponse().getContentAsString());
    }

    private static org.springframework.util.LinkedMultiValueMap<String, String> authorizeParams(String challenge) {
        var p = new org.springframework.util.LinkedMultiValueMap<String, String>();
        p.add("response_type", "code");
        p.add("client_id", "cmt-mobile");
        p.add("scope", "openid profile");
        p.add("redirect_uri", REDIRECT);
        p.add("state", "xyz");
        if (challenge != null) {
            p.add("code_challenge", challenge);
            p.add("code_challenge_method", "S256");
        }
        return p;
    }

    private JWKSet jwks() throws Exception {
        String body = mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();
        return JWKSet.parse(body);
    }

    private boolean verifiesAgainstJwks(SignedJWT jwt) throws Exception {
        RSAKey key = (RSAKey) jwks().getKeyByKeyId(jwt.getHeader().getKeyID());
        return key != null && jwt.verify(new RSASSAVerifier(key));
    }

    private static String randomVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
