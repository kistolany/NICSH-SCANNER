package kh.cmt.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * The whole API on throw-away Postgres + Redis, with MockMvc and tokens from {@link TestJwt}; pushes go to a
 * {@link RecordingPushSender}, and outbox events are handled only when a test calls {@code OutboxRelay.drain()}.
 * Same setup on every test class, so one application context is reused.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, RecordingPushSender.Config.class, TestClock.Config.class})
@ContextConfiguration(initializers = TestJwt.Initializer.class)
public @interface ApiTest {
}
