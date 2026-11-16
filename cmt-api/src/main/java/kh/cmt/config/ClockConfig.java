package kh.cmt.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one clock. Inject {@link Clock}; never call {@code LocalDateTime.now()} directly. */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock(@Value("${cmt.timezone:Asia/Phnom_Penh}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
