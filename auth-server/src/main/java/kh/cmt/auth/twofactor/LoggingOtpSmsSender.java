package kh.cmt.auth.twofactor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Placeholder delivery: the code is logged in the dev profile only; elsewhere nothing is sent. */
@Component
class LoggingOtpSmsSender implements OtpSmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSmsSender.class);

    private final boolean dev;

    LoggingOtpSmsSender(Environment environment) {
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public void send(String phone, String code) {
        if (dev) {
            log.info("DEV SMS code for {}: {}", phone, code);
        } else {
            log.warn("SMS code requested but no SMS gateway is configured");
        }
    }
}
