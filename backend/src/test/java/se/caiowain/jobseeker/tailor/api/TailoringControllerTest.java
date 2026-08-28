package se.caiowain.jobseeker.tailor.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;
import se.caiowain.jobseeker.tailor.select.OllamaSelectionClient;
import se.caiowain.jobseeker.tailor.select.SelectionResult;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TailoringControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean OllamaSelectionClient selectionClient;

    private static final String DESCRIPTION =
            "Vi soker en utvecklare. Har erfarenhet av webbutveckling.";

    private Long jobId;

    @BeforeEach
    void seed() {
        applications.deleteAll();
        profiles.deleteAll();
        documents.deleteAll();

        JobPosting job = new JobPosting();
        job.setFingerprint("fp-api-" + System.nanoTime());
        job.setCanonicalUrl("https://example.se/j");
        job.setTitle("Utvecklare");
        job.setEmployerName("Acme AB");
        job.setDescription(DESCRIPTION);
        job.setApplyUrl("https://example.se/apply");
        job.setAtsVendor(AtsVendor.OTHER);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        jobId = jobs.save(job).getId();

        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1L);
        doc.setSha256("sha-api-" + System.nanoTime());
        doc.setContent(new byte[]{1});
        doc.setExtractedText("Built REST APIs.");
        doc.setUploadedAt(Instant.now());
        documents.save(doc);

        CvProfile p = new CvProfile();
        p.setCvDocument(doc);
        p.setFullName("Cai Wain");
        p.setStatus(ProfileStatus.READY);
        CvExperience exp = new CvExperience();
        exp.setEmployer("Nordic Systems AB");
        exp.setOrdinal(0);
        p.addExperience(exp);
        CvExperienceBullet b = new CvExperienceBullet();
        b.setText("Built REST APIs in Java and Spring Boot.");
        b.setOrdinal(0);
        exp.addBullet(b);
        profiles.save(p);

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());
    }

    @Test
    void tailorReturnsTheApplication() throws Exception {
        mvc.perform(post("/api/jobs/" + jobId + "/tailor"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.coveragePercent").value(100))
                .andExpect(jsonPath("$.requirements[0].text").value("Har erfarenhet av webbutveckling"))
                .andExpect(jsonPath("$.requirements[0].evidence[0].bulletText")
                        .value("Built REST APIs in Java and Spring Boot."));
    }

    @Test
    void returns404ForAnUnknownJob() throws Exception {
        mvc.perform(post("/api/jobs/999999/tailor")).andExpect(status().isNotFound());
    }

    @Test
    void returns409WhenNoProfileIsReady() throws Exception {
        CvProfile p = profiles.findFirstByOrderByIdDesc().orElseThrow();
        p.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(p);

        mvc.perform(post("/api/jobs/" + jobId + "/tailor")).andExpect(status().isConflict());
    }

    @Test
    void returns422WithViolationsWhenTheGuardRejects() throws Exception {
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("a phrase that is nowhere in the ad", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());

        mvc.perform(post("/api/jobs/" + jobId + "/tailor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void getsTheApplicationForAJobAnd404sWhenAbsent() throws Exception {
        mvc.perform(get("/api/jobs/" + jobId + "/application")).andExpect(status().isNotFound());

        mvc.perform(post("/api/jobs/" + jobId + "/tailor")).andExpect(status().isCreated());

        mvc.perform(get("/api/jobs/" + jobId + "/application"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Utvecklare"));
    }

    @Test
    void listsTheQueue() throws Exception {
        mvc.perform(post("/api/jobs/" + jobId + "/tailor")).andExpect(status().isCreated());

        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employerName").value("Acme AB"));
    }

    @Test
    void savesProseApprovesAndDiscards() throws Exception {
        String body = mvc.perform(post("/api/jobs/" + jobId + "/tailor"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = applications.findFirstByJobPostingIdOrderByIdDesc(jobId).orElseThrow().getId();

        mvc.perform(put("/api/applications/" + id + "/letter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prose\":\"Hej! Jag soker tjansten.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letterProse").value("Hej! Jag soker tjansten."));

        mvc.perform(post("/api/applications/" + id + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(delete("/api/applications/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
    }
}
