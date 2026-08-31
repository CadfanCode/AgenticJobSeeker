package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionResult;
import se.caiowain.jobseeker.select.SelectionResult.RequirementSelection;
import se.caiowain.jobseeker.tailor.TailoringService;
import se.caiowain.jobseeker.tailor.domain.*;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Autowired TailoringService tailoringService;

    @MockitoBean PdfRenderer renderer;
    /** Only needed by the re-tailor path exercised in §10.8's test; no test reaches the network. */
    @MockitoBean OllamaSelectionClient selectionClient;

    private Long jobId;
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
        jobId = jobs.save(job).getId();

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

        // Only exercised by the re-tailor test below; stubbed here so it mirrors the seeded
        // application's own requirement/evidence and TailoringService's real guard accepts it.
        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))), List.of(1)))
                .when(selectionClient).select(anyString(), any());
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
    void cvHtmlAndLetterHtmlProduceRealContent() {
        String cvHtml = archiveService.cvHtml(applicationId);
        String letterHtml = archiveService.letterHtml(applicationId);

        assertThat(cvHtml).contains("Built REST APIs in Java").contains("Cai Wain");
        assertThat(letterHtml).contains("Jag söker tjänsten.");
        assertThat(archiveService.cvHtml(applicationId)).isEqualTo(cvHtml);
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePreviewAndTheApprovedDocumentComeFromTheSameHtml() {
        // A forward drift-guard, not a retroactive one: self-invocation is invisible to both
        // @Transactional and a Mockito spy (verified empirically — a spy on this bean would
        // not see approve()'s internal this.cvHtml(...) call either), and with today's fixed
        // input an inlined duplicate of the same logic necessarily produces identical output.
        // No black-box assertion can prove approve() calls cvHtml()/letterHtml() rather than
        // a byte-for-byte copy of their bodies; that has to be read from the source. What
        // this test *does* catch is the next drift: capture what approve() actually handed
        // the renderer, and compare it against a fresh cvHtml()/letterHtml() call afterwards
        // — if a future edit changes cvHtml()/letterHtml() without updating a re-inlined copy
        // in approve(), the captured (stale) HTML stops matching and this fails.
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);

        archiveService.approve(applicationId);

        verify(renderer).renderAll(captor.capture());
        // cvHtml/letterHtml carry no status guard, so they still work after approval.
        assertThat(captor.getValue().get(0)).isEqualTo(archiveService.cvHtml(applicationId));
        assertThat(captor.getValue().get(1)).isEqualTo(archiveService.letterHtml(applicationId));
    }

    @Test
    void anUnknownApplicationIsRejected() {
        assertThatThrownBy(() -> archiveService.approve(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theArchivedAdSurvivesAnIngestRewritingThePosting() {
        // Success criterion §10.6: JobMergeService overwrites job_posting.description on every
        // ingest run, keeping the richest text it has seen so far. The archive holds its own
        // copy precisely so a later reader still sees the ad as it read the day it was answered.
        ApplicationArchive archive = archiveService.approve(applicationId);
        String frozenDescription = archive.getJobDescriptionText();
        assertThat(frozenDescription).contains("erfarenhet av Java");

        JobPosting job = jobs.findById(jobId).orElseThrow();
        job.setDescription("something completely different");
        jobs.saveAndFlush(job);

        ApplicationArchive reloaded = archives.findById(archive.getId()).orElseThrow();
        assertThat(reloaded.getJobDescriptionText()).isEqualTo(frozenDescription);
        assertThat(reloaded.getJobDescriptionText())
                .doesNotContain("something completely different");
    }

    @Test
    void aSecondApprovalForTheSameJobAddsASecondArchiveRow() {
        // Success criterion §10.8, crossed via the real path rather than two direct calls to
        // archiveService.approve() on hand-built rows: approve, discard, re-tailor (which
        // TailoringService.tailor() implements by deleting the original tailored_application
        // and inserting a fresh DRAFT — precisely why the first archive's FK is nullable),
        // then approve again. No unique constraint on tailored_application_id backs this.
        ApplicationArchive first = archiveService.approve(applicationId);

        tailoringService.discard(applicationId);
        TailoredApplication retailored = tailoringService.tailor(jobId);
        assertThat(retailored.getId()).isNotEqualTo(applicationId);
        assertThat(applications.findById(applicationId)).isEmpty();

        ApplicationArchive second = archiveService.approve(retailored.getId());

        assertThat(archives.count()).isEqualTo(2);
        assertThat(second.getId()).isNotEqualTo(first.getId());

        ApplicationArchive firstReloaded = archives.findById(first.getId()).orElseThrow();
        assertThat(firstReloaded.getTailoredApplication()).isNull();
        assertThat(firstReloaded.getJobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(firstReloaded.getCvPdf()).isNotEmpty();

        ApplicationArchive secondReloaded = archives.findById(second.getId()).orElseThrow();
        assertThat(secondReloaded.getCvPdf()).isNotEmpty();
    }
}
