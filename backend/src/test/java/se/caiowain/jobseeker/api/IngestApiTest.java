package se.caiowain.jobseeker.api;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.*;

import java.time.Instant;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IngestApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IngestRunRepository runs;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;
    @Autowired SearchCriteriaRepository criteria;

    @BeforeEach
    void seed() {
        sources.deleteAll();
        postings.deleteAll();
        runs.deleteAll();

        IngestRun run = new IngestRun();
        run.setSource(SourceId.JOBTECH);
        run.setStartedAt(Instant.now());
        run.setFinishedAt(Instant.now());
        run.setFetched(10);
        run.setCreated(7);
        run.setMerged(3);
        run.setStatus(RunStatus.COMPLETED);
        runs.save(run);

        JobPosting job = new JobPosting();
        job.setFingerprint("fp-stats");
        job.setCanonicalUrl("https://example.se/1");
        job.setTitle("Java Developer");
        job.setAtsVendor(AtsVendor.TEAMTAILOR);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        postings.save(job);

        JobPostingSource row = new JobPostingSource();
        row.setJobPosting(job);
        row.setSource(SourceId.JOBTECH);
        row.setSourceAdId("a1");
        row.setFetchedAt(Instant.now());
        sources.save(row);
    }

    @Test
    void listsIngestRuns() throws Exception {
        mvc.perform(get("/api/ingest/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].source").value("JOBTECH"))
                .andExpect(jsonPath("$.content[0].fetched").value(10))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
    }

    @Test
    void returnsStats() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1))
                .andExpect(jsonPath("$.jobsBySource.JOBTECH").value(1))
                .andExpect(jsonPath("$.jobsByVendor.TEAMTAILOR").value(1))
                .andExpect(jsonPath("$.lastRun.source").value("JOBTECH"));
    }

    @Test
    void listsCriteriaSeededByMigration() throws Exception {
        mvc.perform(get("/api/criteria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThan(0)));
    }

    @Test
    void createsCriteria() throws Exception {
        long before = criteria.count();

        mvc.perform(post("/api/criteria")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Kotlin Goteborg","query":"kotlin",
                                 "municipalityCodes":"","municipalityNames":"Goteborg",
                                 "occupationFieldCodes":"apaJ_2ja_LuF","enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Kotlin Goteborg"));

        Assertions.assertThat(criteria.count()).isEqualTo(before + 1);
    }

    @Test
    void triggersAnIngestRunAndIsolatesUnreachableSources() throws Exception {
        // Every real source points at a dead port in tests, so this exercises the
        // endpoint contract and the per-source isolation guarantee at the same time.
        mvc.perform(post("/api/ingest/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
