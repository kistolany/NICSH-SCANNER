package kh.cmt.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Throw-away Postgres 16, Redis 7 and MinIO (object storage) for integration tests. Spring Boot wires the datasource
 * and Redis connection to them automatically ({@code @ServiceConnection}); the files settings are pointed at MinIO
 * here. Tests never touch the dev containers. Use with {@code @Import(TestcontainersConfig.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
    }

    /**
     * MinIO no longer publishes its images on Docker Hub / quay.io (pulls are refused since late 2025), so the image
     * already on the machine is used ({@code minio/minio:latest}, from infra/docker-compose.yml). Set
     * {@code TEST_MINIO_IMAGE} to use another MinIO build (e.g. on CI).
     */
    @Bean
    MinIOContainer minio() {
        String image = System.getenv().getOrDefault("TEST_MINIO_IMAGE", "minio/minio:latest");
        return new MinIOContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("minio/minio"));
    }

    @Bean
    DynamicPropertyRegistrar filesProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("cmt.files.endpoint", minio::getS3URL);
            registry.add("cmt.files.access-key", minio::getUserName);
            registry.add("cmt.files.secret-key", minio::getPassword);
            registry.add("cmt.files.bucket", () -> "cmt-test");
        };
    }
}
