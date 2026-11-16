package kh.cmt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kh.cmt.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
class BaseMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v1CreatedBaseTablesAndOneSchool() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("school", "outbox", "audit_log", "file", "flyway_schema_history");

        Integer v1 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(v1).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "select name_en from school where id = '00000000-0000-0000-0000-000000000001'", String.class))
                .isEqualTo("CMT School");
        assertThat(jdbc.queryForObject("select count(*) from school", Integer.class)).isEqualTo(1);
    }
}
