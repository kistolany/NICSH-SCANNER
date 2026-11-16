package kh.cmt.support;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import kh.cmt.notifications.application.PushSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for FCM in the API tests: keeps every push it is given ("the phone received it"); tokens put in
 * {@link #gone} answer TOKEN_GONE like FCM does for an uninstalled app.
 */
public class RecordingPushSender implements PushSender {

    public final List<PushMessage> sent = new CopyOnWriteArrayList<>();
    public final Set<String> gone = ConcurrentHashMap.newKeySet();

    @Override
    public Result send(PushMessage message) {
        if (gone.contains(message.token())) {
            return Result.TOKEN_GONE;
        }
        sent.add(message);
        return Result.SENT;
    }

    public List<PushMessage> to(String token) {
        return sent.stream().filter(m -> m.token().equals(token)).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        RecordingPushSender recordingPushSender() {
            return new RecordingPushSender();
        }
    }
}
