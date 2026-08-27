package se.caiowain.jobseeker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAllSpineTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "job_posting", "job_posting_source", "ats_tenant",
                "search_criteria", "ingest_run");
    }

    @Test
    void fingerprintIsUnique() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_posting' and indexdef like '%UNIQUE%fingerprint%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void sourceAdIdIsUniquePerSource() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_posting_source' and indexdef like '%UNIQUE%source%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }
}
