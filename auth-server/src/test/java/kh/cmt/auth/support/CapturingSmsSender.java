package kh.cmt.auth.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kh.cmt.auth.twofactor.OtpSmsSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test stand-in for the SMS gateway: remembers the last code sent to each phone number. */
public class CapturingSmsSender implements OtpSmsSender {

    private final Map<String, String> lastCode = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String code) {
        lastCode.put(phone, code);
    }

    public String lastCodeFor(String phone) {
        return lastCode.get(phone);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        CapturingSmsSender capturingSmsSender() {
            return new CapturingSmsSender();
        }
    }
}
