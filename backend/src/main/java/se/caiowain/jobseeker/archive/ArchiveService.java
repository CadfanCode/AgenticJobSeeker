package se.caiowain.jobseeker.archive;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.render.ApplicationDocument;
import se.caiowain.jobseeker.render.DocumentHtmlBuilder;
import se.caiowain.jobseeker.render.PdfRenderer;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Approval: render, hash, freeze.
 *
 * <p>{@link #approve} renders by calling {@link #cvHtml} and {@link #letterHtml}, and the
 * preview endpoint calls those same two methods. That shared call is the whole reason the
 * preview can be trusted — there is no second path that could produce different HTML.
 *
 * <p>{@link #approve} renders both documents with a single call to {@link PdfRenderer#renderAll}
 * rather than two calls to {@link PdfRenderer#render}: a browser launch measured at roughly
 * 4.6s, so rendering the CV and the letter separately would cost a second launch for no reason.
 *
 * <p>No {@code noRollbackFor}: nothing is persisted unless both documents render. A failed
 * approval must leave the application in {@code DRAFT} with no archive row, so a missing
 * browser can never be mistaken for an approved application.
 */
@Service
public class ArchiveService {

    private final ApplicationArchiveRepository archives;
    private final TailoredApplicationRepository applications;
    private final ApplicationDocument document;
    private final DocumentHtmlBuilder html;
    private final PdfRenderer renderer;

    public ArchiveService(ApplicationArchiveRepository archives,
                          TailoredApplicationRepository applications,
                          ApplicationDocument document,
                          DocumentHtmlBuilder html,
                          PdfRenderer renderer) {
        this.archives = archives;
        this.applications = applications;
        this.document = document;
        this.html = html;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public String cvHtml(Long applicationId) {
        TailoredApplication application = require(applicationId);
        return html.cvHtml(document.cv(application, application.getCvProfile()));
    }

    @Transactional(readOnly = true)
    public String letterHtml(Long applicationId) {
        TailoredApplication application = require(applicationId);
        return html.letterHtml(document.letter(application, application.getCvProfile(), today()));
    }

    @Transactional
    public ApplicationArchive approve(Long applicationId) {
        TailoredApplication application = require(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT) {
            throw new ApplicationNotDraftException(
                    "Only a draft can be approved. This application is "
                            + application.getStatus() + ".");
        }

        CvProfile profile = application.getCvProfile();
        JobPosting job = application.getJobPosting();

        PdfRenderer.RenderedDocuments rendered = renderer.renderAll(List.of(
                html.cvHtml(document.cv(application, profile)),
                html.letterHtml(document.letter(application, profile, today()))));
        byte[] cvPdf = rendered.pdfs().get(0);
        byte[] letterPdf = rendered.pdfs().get(1);

        ApplicationArchive archive = new ApplicationArchive();
        archive.setTailoredApplication(application);
        archive.setJobPosting(job);
        archive.setCvProfile(profile);

        // Copied, not referenced: JobMergeService overwrites job_posting.description on
        // re-ingest and employers delete postings. jobApplyUrl is stored for the candidate to
        // read — nothing dereferences it.
        archive.setJobTitle(job.getTitle());
        archive.setEmployerName(job.getEmployerName());
        archive.setJobCanonicalUrl(job.getCanonicalUrl());
        archive.setJobApplyUrl(job.getApplyUrl());
        archive.setJobDescriptionText(job.getDescription());

        archive.setLetterText(application.getLetterProse());
        archive.setCvPdf(cvPdf);
        archive.setCvPdfSha256(sha256(cvPdf));
        archive.setLetterPdf(letterPdf);
        archive.setLetterPdfSha256(sha256(letterPdf));
        archive.setCoveragePercent(application.getCoveragePercent());
        archive.setRenderedBy(rendered.rendererName());
        archive.setApprovedAt(Instant.now());

        ApplicationArchive saved = archives.save(archive);

        application.setStatus(ApplicationStatus.APPROVED);
        application.setReviewedAt(Instant.now());
        applications.save(application);

        return saved;
    }

    private TailoredApplication require(Long id) {
        return applications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No application with id " + id));
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
