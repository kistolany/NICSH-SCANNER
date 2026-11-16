package kh.cmt.notifications.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import kh.cmt.notifications.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Firebase Cloud Messaging, HTTP v1 API. Signs in with the project's service account (the JSON key file from the
 * Firebase console, {@code cmt.push.fcm.credentials-file}): a JWT signed with its private key is exchanged for an
 * access token (kept until a minute before it ends), then {@code POST /v1/projects/{project}/messages:send}.
 * A token the phone no longer has (404 UNREGISTERED, 400 INVALID_ARGUMENT) is {@code TOKEN_GONE}.
 */
class FcmPushSender implements PushSender {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final ServiceAccount account;
    private final RestClient http;
    private final String apiUrl;
    private final Clock clock;
    private String accessToken;
    private Instant accessTokenUntil = Instant.EPOCH;

    record ServiceAccount(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {

        static ServiceAccount read(Path file, ObjectMapper json) {
            try {
                JsonNode n = json.readTree(Files.readString(file));
                String pem = n.path("private_key").asText();
                String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
                String tokenUri = n.path("token_uri").asText("https://oauth2.googleapis.com/token");
                return new ServiceAccount(n.path("project_id").asText(), n.path("client_email").asText(), key, tokenUri);
            } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException("Cannot read the Firebase service account file " + file, e);
            }
        }
    }

    FcmPushSender(ServiceAccount account, RestClient.Builder http, String apiUrl, Clock clock) {
        this.account = account;
        this.http = http.build();
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.clock = clock;
    }

    @Override
    public Result send(PushMessage message) {
        try {
            return post(message, false);
        } catch (RuntimeException e) {
            log.warn("Push to FCM failed: {}", e.toString());
            return Result.FAILED;
        }
    }

    private Result post(PushMessage message, boolean retried) {
        Map<String, Object> body = Map.of("message", Map.of(
                "token", message.token(),
                "notification", Map.of("title", message.title(), "body", message.body()),
                "data", message.data(),
                "android", Map.of("priority", "high"),
                "apns", Map.of("headers", Map.of("apns-priority", "10"))));
        try {
            http.post().uri(apiUrl + "/v1/projects/" + account.projectId() + "/messages:send")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return Result.SENT;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String error = e.getResponseBodyAsString();
            if (status.value() == 401 && !retried) {
                accessTokenUntil = Instant.EPOCH;   // the access token was refused: get a new one, once
                return post(message, true);
            }
            if (status.value() == 404 || (status.value() == 400 && error.contains("INVALID_ARGUMENT"))) {
                return Result.TOKEN_GONE;
            }
            log.warn("FCM refused a push: {} {}", status.value(), error.length() > 300 ? error.substring(0, 300) : error);
            return Result.FAILED;
        }
    }

    /** A Google access token for FCM, from the service account; renewed a minute before it ends. */
    synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenUntil)) {
            return accessToken;
        }
        String assertion;
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(account.clientEmail())
                    .audience(account.tokenUri())
                    .claim("scope", SCOPE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(account.privateKey()));
            assertion = jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the FCM token request", e);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion);
        JsonNode answer = http.post().uri(account.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JsonNode.class);
        if (answer == null || !answer.hasNonNull("access_token")) {
            throw new IllegalStateException("Google gave no access token for FCM");
        }
        accessToken = answer.get("access_token").asText();
        accessTokenUntil = now.plusSeconds(Math.max(60, answer.path("expires_in").asLong(3600) - 60));
        return accessToken;
    }
}
