package se.caiowain.jobseeker.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CvSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAllProfileTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "cv_document", "cv_profile", "cv_experience",
                "cv_experience_bullet", "cv_education", "cv_skill");
    }

    @Test
    void documentHashIsUnique() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'cv_document' and indexdef like '%UNIQUE%sha256%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void datesAreStoredAsTextNotDate() {
        String type = jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = 'cv_experience' and column_name = 'start_date'
                """, String.class);
        assertThat(type).isEqualTo("character varying");
    }

    @Test
    void applicationStartsWithoutAnApiKey() {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }
}
