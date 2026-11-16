package kh.cmt.notifications.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import kh.cmt.notifications.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Push: FCM when {@code cmt.push.fcm.credentials-file} (env {@code FCM_CREDENTIALS_FILE}) names the Firebase service
 * account key, else pushes are only logged.
 */
@Configuration
class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(@Value("${cmt.push.fcm.credentials-file:}") String credentialsFile,
            @Value("${cmt.push.fcm.api-url:https://fcm.googleapis.com}") String apiUrl, RestClient.Builder http, ObjectMapper json,
            Clock clock) {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            log.info("Push notifications are off: no Firebase credentials (cmt.push.fcm.credentials-file)");
            return new LoggingPushSender();
        }
        FcmPushSender.ServiceAccount account = FcmPushSender.ServiceAccount.read(Path.of(credentialsFile), json);
        log.info("Push notifications go through FCM project {}", account.projectId());
        return new FcmPushSender(account, http, apiUrl, clock);
    }
}
