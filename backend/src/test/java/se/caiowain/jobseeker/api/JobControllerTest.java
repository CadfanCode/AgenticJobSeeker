package se.caiowain.jobseeker.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JobControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;

    private Long javaJobId;

    @BeforeEach
    void seed() {
        sources.deleteAll();
        postings.deleteAll();

        JobPosting java = save("fp-java", "Senior Java Developer", "Stockholm", AtsVendor.TEAMTAILOR,
                "Vi soker en Java-utvecklare");
        save("fp-nurse", "Sjukskoterska", "Malmo", AtsVendor.VARBI, "Vard och omsorg");
        javaJobId = java.getId();

        JobPostingSource row = new JobPostingSource();
        row.setJobPosting(java);
        row.setSource(SourceId.JOBTECH);
        row.setSourceAdId("31404250");
        row.setSourceUrl("https://arbetsformedlingen.se/platsbanken/annonser/31404250");
        row.setFetchedAt(Instant.now());
        sources.save(row);
    }

    private JobPosting save(String fingerprint, String title, String municipality,
                            AtsVendor vendor, String description) {
        JobPosting job = new JobPosting();
        job.setFingerprint(fingerprint);
        job.setCanonicalUrl("https://example.se/" + fingerprint);
        job.setTitle(title);
        job.setEmployerName("Acme AB");
        job.setMunicipality(municipality);
        job.setDescription(description);
        job.setAtsVendor(vendor);
        job.setStatus(JobStatus.DISCOVERED);
        job.setPublishedAt(Instant.now());
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        return postings.save(job);
    }

    @Test
    void listsAllJobs() throws Exception {
        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void filtersByFreeTextQuery() throws Exception {
        mvc.perform(get("/api/jobs").param("q", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Senior Java Developer"));
    }

    @Test
    void filtersByMunicipality() throws Exception {
        mvc.perform(get("/api/jobs").param("municipality", "Malmo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Sjukskoterska"));
    }

    @Test
    void filtersByVendor() throws Exception {
        mvc.perform(get("/api/jobs").param("vendor", "TEAMTAILOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void filtersBySource() throws Exception {
        mvc.perform(get("/api/jobs").param("source", "JOBTECH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Senior Java Developer"));
    }

    @Test
    void paginates() throws Exception {
        mvc.perform(get("/api/jobs").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void returnsDetailWithContributingSources() throws Exception {
        mvc.perform(get("/api/jobs/" + javaJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Senior Java Developer"))
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].source").value("JOBTECH"))
                .andExpect(jsonPath("$.sources[0].sourceAdId").value("31404250"));
    }

    @Test
    void returns404ForUnknownJob() throws Exception {
        mvc.perform(get("/api/jobs/999999")).andExpect(status().isNotFound());
    }
}
