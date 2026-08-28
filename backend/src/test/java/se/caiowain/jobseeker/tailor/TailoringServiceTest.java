package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;
import se.caiowain.jobseeker.tailor.select.OllamaSelectionClient;
import se.caiowain.jobseeker.tailor.select.SelectionResult;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
class TailoringServiceTest extends AbstractIntegrationTest {

    @Autowired TailoringService service;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    /** The only model caller is replaced; no test reaches the network. */
    @MockitoBean OllamaSelectionClient selectionClient;

    private static final String DESCRIPTION =
            "Vi soker en utvecklare. Har erfarenhet av webbutveckling. Har god forstaelse API-design.";

    private Long jobId;
    private CvProfile profile;

    @BeforeEach
    void seed() {
        applications.deleteAll();
        profiles.deleteAll();
        documents.deleteAll();

        JobPosting job = new JobPosting();
        job.setFingerprint("fp-svc-" + System.nanoTime());
        job.setCanonicalUrl("https://example.se/j");
        job.setTitle("Utvecklare");
        job.setEmployerName("Acme AB");
        job.setDescription(DESCRIPTION);
        job.setAtsVendor(AtsVendor.OTHER);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        jobId = jobs.save(job).getId();

        profile = readyProfile();

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());
    }

    private CvProfile readyProfile() {
        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1L);
        doc.setSha256("sha-svc-" + System.nanoTime());
        doc.setContent(new byte[]{1});
        doc.setExtractedText("Built REST APIs in Java and Spring Boot.");
        doc.setUploadedAt(Instant.now());
        documents.save(doc);

        CvProfile p = new CvProfile();
        p.setCvDocument(doc);
        p.setFullName("Cai Wain");
        p.setStatus(ProfileStatus.READY);

        CvExperience exp = new CvExperience();
        exp.setEmployer("Nordic Systems AB");
        exp.setTitle("Senior Software Engineer");
        exp.setOrdinal(0);
        p.addExperience(exp);

        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built REST APIs in Java and Spring Boot.");
        bullet.setOrdinal(0);
        exp.addBullet(bullet);

        return profiles.save(p);
    }

    @Test
    void tailorProducesADraftBuiltFromProfileBullets() {
        TailoredApplication app = service.tailor(jobId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app.getRequirements()).hasSize(1);
        assertThat(app.getRequirements().getFirst().getEvidence().getFirst().getBulletText())
                .isEqualTo("Built REST APIs in Java and Spring Boot.");
        assertThat(app.getCoveragePercent()).isEqualTo(100);
    }

    @Test
    void refusesWhenNoProfileIsReady() {
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(profile);

        assertThatThrownBy(() -> service.tailor(jobId))
                .isInstanceOf(ProfileNotReadyException.class);
    }

    @Test
    void retriesOnceThenRejectsAFabricatedRequirement() {
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av 2nd line support", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());

        assertThatThrownBy(() -> service.tailor(jobId))
                .isInstanceOf(TailoringRejectedException.class);

        verify(selectionClient, times(2)).select(anyString(), any());
        assertThat(applications.findFirstByJobPostingIdOrderByIdDesc(jobId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.GENERATION_FAILED);
    }

    @Test
    void aSecondAttemptThatPassesIsAccepted() {
        doReturn(new SelectionResult(List.of(
                        new RequirementSelection("not in the ad at all", List.of(1))), List.of(1)))
                .doReturn(new SelectionResult(List.of(
                        new RequirementSelection("Har god forstaelse API-design", List.of(1))), List.of(1)))
                .when(selectionClient).select(anyString(), any());

        TailoredApplication app = service.tailor(jobId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        verify(selectionClient, times(2)).select(anyString(), any());
    }

    @Test
    void reTailoringReplacesADraft() {
        service.tailor(jobId);
        service.tailor(jobId);

        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void reTailoringRefusesToOverwriteAnApprovedApplication() {
        TailoredApplication app = service.tailor(jobId);
        service.approve(app.getId());

        assertThatThrownBy(() -> service.tailor(jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Discard");
    }

    @Test
    void approveFreezesAndDiscardReleases() {
        TailoredApplication app = service.tailor(jobId);

        service.approve(app.getId());
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.APPROVED);

        service.discard(app.getId());
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.DISCARDED);

        assertThat(service.tailor(jobId).getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void savesTheProseYouWrote() {
        TailoredApplication app = service.tailor(jobId);

        service.saveLetter(app.getId(), "Hej! Jag söker tjänsten eftersom...");

        assertThat(applications.findById(app.getId()).orElseThrow().getLetterProse())
                .startsWith("Hej!");
    }
}
