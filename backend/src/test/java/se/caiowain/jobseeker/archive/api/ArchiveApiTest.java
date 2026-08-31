package se.caiowain.jobseeker.archive.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.render.PdfRenderer;
import se.caiowain.jobseeker.render.RendererUnavailableException;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.*;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code renderAll} is the only renderer method stubbed here, matching {@link
 * se.caiowain.jobseeker.archive.ArchiveServiceTest}: {@code render} delegates to it (Task 5),
 * and {@code approve} renders both documents in a single call to {@code renderAll}, so a stub on
 * {@code render} would never fire. The two stubbed PDFs use distinct bytes so a CV/letter
 * mix-up would show up in {@link #thePdfsAreServedAsPdf}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArchiveApiTest extends AbstractIntegrationTest {

    private static final byte[] CV_PDF_BYTES = "%PDF-cv-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LETTER_PDF_BYTES = "%PDF-letter-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired MockMvc mvc;
    @Autowired ApplicationArchiveRepository archives;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean PdfRenderer renderer;

    private Long applicationId;

    @BeforeEach
    void seed() {
        archives.deleteAllInBatch();
        applications.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        CvDocument document = new CvDocument();
        document.setFilename("cv.pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(4);
        document.setSha256("e".repeat(64));
        document.setContent("%PDF".getBytes(StandardCharsets.UTF_8));
        document.setUploadedAt(Instant.parse("2026-08-31T08:00:00Z"));
        documents.save(document);

        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setFullName("Cai Wain");
        profile.setStatus(ProfileStatus.READY);
        CvExperience experience = new CvExperience();
        experience.setEmployer("Acme AB");
        experience.setTitle("Backend Developer");
        experience.setOrdinal(0);
        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built REST APIs in Java");
        bullet.setOrdinal(0);
        experience.addBullet(bullet);
        profile.addExperience(experience);
        profiles.saveAndFlush(profile);

        JobPosting job = new JobPosting();
        job.setFingerprint("api-arch-1");
        job.setCanonicalUrl("https://example.test/jobs/api-arch-1");
        job.setTitle("Plattformsingenjör");
        job.setEmployerName("Example AB");
        job.setDescription("Vi söker en utvecklare med erfarenhet av Java.");
        job.setApplyUrl("https://example.test/apply/api-arch-1");
        job.setFirstSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setLastSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        jobs.save(job);

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);
        application.setCvProfile(profile);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setCoveragePercent(100);
        application.setGeneratedAt(Instant.parse("2026-08-31T09:00:00Z"));
        application.setLetterProse("Hej,\n\nJag söker tjänsten.");
        applicationId = applications.saveAndFlush(application).getId();

        doReturn(new PdfRenderer.RenderedDocuments(List.of(CV_PDF_BYTES, LETTER_PDF_BYTES), "chromium/mocked"))
                .when(renderer).renderAll(any());
    }

    @Test
    void previewReturnsTheDocumentAsHtml() throws Exception {
        mvc.perform(get("/api/applications/" + applicationId + "/preview/cv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Built REST APIs in Java")));
    }

    @Test
    void previewReturnsTheLetterToo() throws Exception {
        mvc.perform(get("/api/applications/" + applicationId + "/preview/letter"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Jag söker tjänsten.")));
    }

    @Test
    void previewForAnUnknownApplicationIsFourOhFour() throws Exception {
        mvc.perform(get("/api/applications/999999/preview/cv")).andExpect(status().isNotFound());
    }

    @Test
    void approvingArchivesAndReturnsTheRow() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Plattformsingenjör"))
                .andExpect(jsonPath("$.cvPdfSha256").isNotEmpty());
    }

    @Test
    void approvingTwiceIsFourOhNine() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));
        mvc.perform(post("/api/applications/" + applicationId + "/approve"))
                .andExpect(status().isConflict());
    }

    @Test
    void approvingWithNoBrowserIsFiveOhThree() throws Exception {
        doThrow(new RendererUnavailableException("no browser", new IllegalStateException()))
                .when(renderer).renderAll(any());

        mvc.perform(post("/api/applications/" + applicationId + "/approve"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void theArchiveListsWhatWasApproved() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));

        mvc.perform(get("/api/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employerName").value("Example AB"));
    }

    @Test
    void anArchivedRowCarriesTheAdAndTheLetterText() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));
        Long archiveId = archives.findAll().getFirst().getId();

        mvc.perform(get("/api/archive/" + archiveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobDescriptionText").value(
                        org.hamcrest.Matchers.containsString("erfarenhet av Java")))
                .andExpect(jsonPath("$.letterText").value(
                        org.hamcrest.Matchers.containsString("Jag söker tjänsten.")));
    }

    @Test
    void thePdfsAreServedAsPdf() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));
        Long archiveId = archives.findAll().getFirst().getId();

        // Distinct stubbed bytes per document, so a CV/letter mix-up in the controller or the
        // service would fail this rather than pass by coincidence.
        mvc.perform(get("/api/archive/" + archiveId + "/cv.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(CV_PDF_BYTES));
        mvc.perform(get("/api/archive/" + archiveId + "/letter.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(LETTER_PDF_BYTES));
    }

    @Test
    void anUnknownArchiveRowIsFourOhFour() throws Exception {
        mvc.perform(get("/api/archive/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/archive/999999/cv.pdf")).andExpect(status().isNotFound());
    }
}
