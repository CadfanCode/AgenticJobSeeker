package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * The renderer is mocked so the archive's own logic is testable without a browser — the same
 * arrangement Slices 2b and 2d use for the Ollama client. Real rendering is covered by
 * PdfRendererTest, which skips when Chromium is absent.
 *
 * <p>{@code renderAll} is the only renderer method stubbed here: {@code render} delegates to
 * it (Task 5), and {@code approve} renders both documents in one call to {@code renderAll}, so
 * stubbing the delegate is what matters. The two stubbed documents use distinct bytes so the
 * CV and letter can be told apart in assertions.
 */
@SpringBootTest
class ArchiveServiceTest extends AbstractIntegrationTest {

    private static final byte[] CV_BYTES = "%PDF-cv".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LETTER_BYTES = "%PDF-letter".getBytes(StandardCharsets.UTF_8);

    @Autowired ArchiveService archiveService;
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
        document.setSha256("f".repeat(64));
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
        job.setFingerprint("arch-1");
        job.setCanonicalUrl("https://example.test/jobs/arch-1");
        job.setTitle("Plattformsingenjör");
        job.setEmployerName("Example AB");
        job.setDescription("Vi söker en utvecklare med erfarenhet av Java.");
        job.setApplyUrl("https://example.test/apply/arch-1");
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
        ApplicationRequirement requirement = new ApplicationRequirement();
        requirement.setText("erfarenhet av Java");
        requirement.setOrdinal(0);
        ApplicationEvidence evidence = new ApplicationEvidence();
        evidence.setBulletText("Built REST APIs in Java");
        evidence.setOrdinal(0);
        requirement.addEvidence(evidence);
        application.addRequirement(requirement);
        applicationId = applications.saveAndFlush(application).getId();

        doReturn(new PdfRenderer.RenderedDocuments(List.of(CV_BYTES, LETTER_BYTES), "chromium/mocked"))
                .when(renderer).renderAll(any());
    }

    @Test
    void approvingFreezesTheDocumentsAndTheAd() {
        ApplicationArchive archive = archiveService.approve(applicationId);

        assertThat(archive.getJobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(archive.getEmployerName()).isEqualTo("Example AB");
        assertThat(archive.getJobDescriptionText()).contains("erfarenhet av Java");
        assertThat(archive.getLetterText()).isEqualTo("Hej,\n\nJag söker tjänsten.");
        // The list order out of renderAll must map cv -> index 0, letter -> index 1.
        assertThat(archive.getCvPdf()).isEqualTo(CV_BYTES);
        assertThat(archive.getLetterPdf()).isEqualTo(LETTER_BYTES);
        assertThat(archive.getRenderedBy()).isEqualTo("chromium/mocked");
        assertThat(archive.getApprovedAt()).isNotNull();
    }

    @Test
    void approvingHashesEachFile() {
        ApplicationArchive archive = archiveService.approve(applicationId);

        // The point is that the digest is of the stored bytes, so an archive row can prove
        // which file it holds rather than merely describing one.
        assertThat(archive.getCvPdfSha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(archive.getLetterPdfSha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(archive.getCvPdfSha256()).isNotEqualTo(archive.getLetterPdfSha256());
    }

    @Test
    void approvingFlipsTheApplicationToApproved() {
        archiveService.approve(applicationId);

        TailoredApplication reloaded = applications.findById(applicationId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(reloaded.getReviewedAt()).isNotNull();
    }

    @Test
    void approvingSomethingAlreadyApprovedIsRejected() {
        archiveService.approve(applicationId);

        assertThatThrownBy(() -> archiveService.approve(applicationId))
                .isInstanceOf(ApplicationNotDraftException.class);

        assertThat(archives.count()).isEqualTo(1);
    }

    @Test
    void theArchiveDoesNotMoveWhenTheProfileChangesAfterwards() {
        ApplicationArchive archive = archiveService.approve(applicationId);
        byte[] frozen = archive.getCvPdf();

        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setFullName("Someone Else Entirely");
        profiles.saveAndFlush(profile);

        assertThat(archives.findById(archive.getId()).orElseThrow().getCvPdf()).isEqualTo(frozen);
    }

    @Test
    void theArchiveSurvivesTheApplicationBeingDeleted() {
        // Slice 2b replaces a DRAFT or DISCARDED application outright on re-tailor. Every
        // approval must still survive that — it is the entire reason the table exists.
        ApplicationArchive archive = archiveService.approve(applicationId);
        Long archiveId = archive.getId();

        applications.deleteById(applicationId);
        applications.flush();

        ApplicationArchive survivor = archives.findById(archiveId).orElseThrow();
        assertThat(survivor.getTailoredApplication()).isNull();
        assertThat(survivor.getJobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(survivor.getCvPdf()).isNotEmpty();
    }

    @Test
    void aRenderFailurePersistsNothing() {
        doThrow(new RendererUnavailableException("no browser", new IllegalStateException()))
                .when(renderer).renderAll(any());

        assertThatThrownBy(() -> archiveService.approve(applicationId))
                .isInstanceOf(RendererUnavailableException.class);

        assertThat(archives.count()).isZero();
        assertThat(applications.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void thePreviewAndTheApprovedDocumentComeFromTheSameHtml() {
        // The no-drift guarantee, asserted rather than asserted-about: approve() renders by
        // calling these same two methods.
        String cvHtml = archiveService.cvHtml(applicationId);
        String letterHtml = archiveService.letterHtml(applicationId);

        assertThat(cvHtml).contains("Built REST APIs in Java").contains("Cai Wain");
        assertThat(letterHtml).contains("Jag söker tjänsten.");
        assertThat(archiveService.cvHtml(applicationId)).isEqualTo(cvHtml);
    }

    @Test
    void anUnknownApplicationIsRejected() {
        assertThatThrownBy(() -> archiveService.approve(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
