package kh.cmt.auth.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Full auth server on throw-away Postgres + Redis. Same setup on every test class, so one context is reused. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, CapturingSmsSender.Config.class})
// the other tests log in a lot from one address; they use this server's own sign-in pages (WebLoginTest: the admin web's)
@TestPropertySource(properties = {"auth.rate-limit.login.capacity=100000", "auth.web.login-url=", "auth.web.allowed-origins="})
public @interface AuthServerTest {
}
