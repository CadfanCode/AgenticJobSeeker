package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ArchiveSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTheArchiveTable() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains("application_archive");
    }

    @Test
    void everyForeignKeyIsNullableAndSetsNullOnDelete() {
        // The archive must survive the application, the posting and the profile it came from.
        // A cascade here would let a re-tailor delete history, which is the one thing this
        // table exists to prevent.
        List<String> nullability = jdbc.queryForList("""
                select is_nullable from information_schema.columns
                where table_name = 'application_archive'
                  and column_name in
                      ('tailored_application_id', 'job_posting_id', 'cv_profile_id')
                """, String.class);

        assertThat(nullability).hasSize(3).allMatch("YES"::equals);

        Integer setNullRules = jdbc.queryForObject("""
                select count(*) from information_schema.referential_constraints rc
                join information_schema.table_constraints tc
                  on rc.constraint_name = tc.constraint_name
                where tc.table_name = 'application_archive'
                  and rc.delete_rule = 'SET NULL'
                """, Integer.class);

        assertThat(setNullRules).isEqualTo(3);
    }

    @Test
    void oneJobMayBeArchivedMoreThanOnce() {
        // Discard, re-tailor and approve again must produce a second row rather than
        // overwriting the first. A unique constraint here would lose an application that
        // was genuinely sent.
        Integer uniqueOnApplication = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'application_archive'
                  and indexdef like '%UNIQUE%tailored_application_id%'
                """, Integer.class);

        assertThat(uniqueOnApplication).isZero();
    }

    @Test
    void thePdfColumnsAreByteaAndNotNull() {
        // bytea, never oid: @Lob maps to oid and Slice 2a already hit that on cv_document.
        List<String> types = jdbc.queryForList("""
                select data_type from information_schema.columns
                where table_name = 'application_archive'
                  and column_name in ('cv_pdf', 'letter_pdf')
                """, String.class);

        assertThat(types).hasSize(2).allMatch("bytea"::equals);

        List<String> nullability = jdbc.queryForList("""
                select is_nullable from information_schema.columns
                where table_name = 'application_archive'
                  and column_name in ('cv_pdf', 'letter_pdf', 'cv_pdf_sha256', 'letter_pdf_sha256')
                """, String.class);

        assertThat(nullability).hasSize(4).allMatch("NO"::equals);
    }

    @Test
    void theAdIsCopiedIntoTheArchiveRatherThanReferenced() {
        // JobMergeService overwrites job_posting.description on re-ingest, so a reference
        // would show a future reader a different job than the one that was answered.
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'application_archive'
                """, String.class);

        assertThat(columns).contains(
                "job_title", "employer_name", "job_canonical_url", "job_apply_url",
                "job_description_text", "letter_text");
    }
}
