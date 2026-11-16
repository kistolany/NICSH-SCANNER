package kh.cmtkernel;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kh.cmt.config.ClockConfig;
import kh.cmt.config.JpaConfig;
import kh.cmt.shared.domain.SchoolIdListener;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks BaseEntity, SoftDeletable and LocalizedText against a Testcontainers Postgres, using a
 * throw-away table (kernel_test_item, created by Hibernate).
 */
@SpringBootTest(classes = SharedKernelPersistenceTest.TestApp.class, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class SharedKernelPersistenceTest {

    static final UUID SCHOOL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Configuration
    @EnableAutoConfiguration
    @EntityScan("kh.cmtkernel")
    @EnableJpaRepositories("kh.cmtkernel")
    @Import({ClockConfig.class, JpaConfig.class, SchoolIdListener.class, TestcontainersConfig.class})
    static class TestApp {
        @Bean
        SchoolContext schoolContext() {
            return () -> SCHOOL;
        }
    }

    @Autowired KernelEntityRepository repo;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    @Test
    void fillsAuditFieldsSchoolAndColumnsAndHidesInactive() {
        Instant before = clock.instant();
        KernelEntity active = repo.save(new KernelEntity(new LocalizedText("ក", "Active", "أ")));
        KernelEntity gone = repo.save(new KernelEntity(new LocalizedText("ខ", "Gone", "ب")));

        assertThat(active.getId()).isNotNull();
        assertThat(active.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(active.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(active.getUpdatedAt()).isNotNull();
        assertThat(active.getVersion()).isZero();

        tx.executeWithoutResult(s -> repo.findById(gone.getId()).orElseThrow()
                .inactivate(UUID.randomUUID(), clock.instant(), "left school"));

        // column naming: name_km / name_en / name_ar
        Object en = tx.execute(s -> em.createNativeQuery(
                "select name_en from kernel_test_item where id = :id")
                .setParameter("id", gone.getId()).getSingleResult());
        assertThat(en).isEqualTo("Gone");

        List<KernelEntity> visible = tx.execute(s -> repo.findAll());
        assertThat(visible).extracting(e -> e.getName().getEn()).contains("Active").doesNotContain("Gone");

        List<KernelEntity> all = tx.execute(s -> {
            SoftDeleteFilter.includeInactive(em);
            return repo.findAll();
        });
        assertThat(all).extracting(e -> e.getName().getEn()).contains("Active", "Gone");
    }

    @Test
    void clockUsesPhnomPenhZone() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Phnom_Penh"));
    }
}
