package se.caiowain.jobseeker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobseekerApplicationTests extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAgainstPostgres() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertThat(version).contains("PostgreSQL");
    }
}
