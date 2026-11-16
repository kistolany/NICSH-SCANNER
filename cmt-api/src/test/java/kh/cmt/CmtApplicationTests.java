package kh.cmt;

import static org.assertj.core.api.Assertions.assertThat;

import kh.cmt.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
class CmtApplicationTests {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void contextLoadsAndRedisContainerIsReachable() {
        redis.opsForValue().set("ping", "pong");
        assertThat(redis.opsForValue().get("ping")).isEqualTo("pong");
    }
}
