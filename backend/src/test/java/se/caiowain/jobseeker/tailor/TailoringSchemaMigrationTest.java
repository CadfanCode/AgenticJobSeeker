package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TailoringSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTailoringTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "tailored_application", "application_requirement", "application_evidence");
    }

    @Test
    void oneApplicationPerJobAndProfile() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'tailored_application'
                  and indexdef like '%UNIQUE%job_posting_id%cv_profile_id%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void evidenceKeepsATextSnapshotAndTolerationForDeletedBullets() {
        String nullable = jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'application_evidence' and column_name = 'cv_experience_bullet_id'
                """, String.class);
        String snapshotNullable = jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'application_evidence' and column_name = 'bullet_text'
                """, String.class);

        assertThat(nullable).isEqualTo("YES");
        assertThat(snapshotNullable).isEqualTo("NO");
    }
}
