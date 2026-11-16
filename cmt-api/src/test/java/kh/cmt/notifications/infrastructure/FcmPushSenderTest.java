package kh.cmt.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import kh.cmt.notifications.application.PushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** FCM HTTP v1 with a made-up service account: sign-in with a signed JWT, the message, a token that is gone. */
class FcmPushSenderTest {

    static final String TOKEN_URI = "https://oauth2.test/token";
    static final String SEND = "https://fcm.test/v1/projects/cmt-test/messages:send";

    @TempDir Path dir;
    private KeyPair keys;
    private MockRestServiceServer google;
    private FcmPushSender sender;

    @BeforeEach
    void aServiceAccount() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        ObjectMapper json = new ObjectMapper();
        Path file = dir.resolve("service-account.json");
        Files.writeString(file, json.writeValueAsString(Map.of("type", "service_account", "project_id", "cmt-test",
                "client_email", "push@cmt-test.iam.gserviceaccount.com", "private_key", pem, "token_uri", TOKEN_URI)));
        RestClient.Builder http = RestClient.builder();
        google = MockRestServiceServer.bindTo(http).build();
        sender = new FcmPushSender(FcmPushSender.ServiceAccount.read(file, json), http, "https://fcm.test", Clock.systemUTC());
    }

    @Test
    void signsInOnceAndSendsTheMessage() {
        AtomicReference<String> assertion = new AtomicReference<>();
        google.expect(ExpectedCount.once(), requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(request -> assertion.set(request.getBody().toString()))
                .andRespond(withSuccess("{\"access_token\":\"ya29.test\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));
        google.expect(ExpectedCount.twice(), requestTo(SEND)).andExpect(header("Authorization", "Bearer ya29.test"))
                .andExpect(jsonPath("$.message.token").value("phone-1"))
                .andExpect(jsonPath("$.message.notification.title").value("Schedule changed"))
                .andExpect(jsonPath("$.message.data.code").value("SCHEDULE_DAYS_CHANGED"))
                .andExpect(jsonPath("$.message.android.priority").value("high"))
                .andRespond(withSuccess("{\"name\":\"projects/cmt-test/messages/1\"}", MediaType.APPLICATION_JSON));

        PushSender.PushMessage m = new PushSender.PushMessage("phone-1", "Schedule changed", "Your schedule…",
                Map.of("code", "SCHEDULE_DAYS_CHANGED"));
        assertThat(sender.send(m)).isEqualTo(PushSender.Result.SENT);
        assertThat(sender.send(m)).isEqualTo(PushSender.Result.SENT);   // the access token is kept
        google.verify();

        String jwt = java.net.URLDecoder.decode(assertion.get().replaceAll(".*assertion=([^&]*).*", "$1"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(assertion.get()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
        assertThat(java.util.Optional.of(jwt).map(s -> {
            try {
                SignedJWT parsed = SignedJWT.parse(s);
                return parsed.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))
                        && parsed.getJWTClaimsSet().getIssuer().equals("push@cmt-test.iam.gserviceaccount.com")
                        && parsed.getJWTClaimsSet().getAudience().contains(TOKEN_URI)
                        && FcmPushSender.SCOPE.equals(parsed.getJWTClaimsSet().getClaim("scope"));
            } catch (Exception e) {
                return false;
            }
        })).contains(true);
    }

    @Test
    void aTokenThePhoneNoLongerHas() {
        google.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"t\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));
        google.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"));
        google.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        PushSender.PushMessage m = new PushSender.PushMessage("old", "t", "b", Map.of());
        assertThat(sender.send(m)).isEqualTo(PushSender.Result.TOKEN_GONE);
        assertThat(sender.send(m)).isEqualTo(PushSender.Result.FAILED);
        google.verify();
    }
}
