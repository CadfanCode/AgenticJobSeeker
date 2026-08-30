package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FitSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTheFitTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "job_preferences", "candidate_language",
                "job_prescreen", "job_deep_fit", "job_deep_fit_gap", "job_triage");
    }

    @Test
    void jobPostingGainsAGeneratedSearchVector() {
        String generated = jdbc.queryForObject("""
                select is_generated from information_schema.columns
                where table_name = 'job_posting' and column_name = 'search_tsv'
                """, String.class);

        assertThat(generated).isEqualTo("ALWAYS");
    }

    @Test
    void theSearchVectorIsIndexedForSearch() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_posting' and indexdef like '%gin%search_tsv%'
                """, Integer.class);

        assertThat(count).isGreaterThan(0);
    }

    @Test
    void thePreferencesSingletonIsSeeded() {
        Integer rows = jdbc.queryForObject("select count(*) from job_preferences", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void atMostOnePrescreenRowPerPosting() {
        // Enforced in the database, not assumed: the prescreen truncates and rebuilds for
        // one profile, so two rows for one posting would mean a bug had already happened.
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_prescreen' and indexdef like '%UNIQUE%job_posting_id%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void deepFitIsKeptPerProfileSoSupersededScoresSurviveAsHistory() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_deep_fit'
                  and indexdef like '%UNIQUE%job_posting_id%cv_profile_id%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void nothingPreservedDependsOnTheDisposableTable() {
        // The load-bearing constraint of the whole slice: a prescreen rebuild truncates
        // job_prescreen, so a foreign key pointing at it from a preserved table would
        // cascade away your triage decisions or a 94-second deep score.
        Integer references = jdbc.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name
                where tc.constraint_type = 'FOREIGN KEY'
                  and ccu.table_name = 'job_prescreen'
                """, Integer.class);

        assertThat(references).isZero();
    }
}
