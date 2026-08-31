package se.caiowain.jobseeker.archive.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.time.Instant;

/**
 * What was approved, frozen at the moment of approval.
 *
 * <p>Every association is optional and set to null when its target is deleted. That is
 * deliberate: {@code tailored_application} is working state a re-tailor may replace outright,
 * job postings are removed by employers, and a CV profile is superseded on re-upload. An
 * archive that any of those could delete would not be an archive, so the row carries its own
 * copy of everything needed to read it — including the ad, because
 * {@code JobMergeService} overwrites {@code job_posting.description} on re-ingest.
 *
 * <p>{@code jobApplyUrl} is stored for the candidate to read. Nothing dereferences it.
 */
@Entity
@Table(name = "application_archive")
public class ApplicationArchive {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tailored_application_id")
    private TailoredApplication tailoredApplication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_posting_id")
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cv_profile_id")
    private CvProfile cvProfile;

    @Column(name = "job_title", nullable = false, length = 512) private String jobTitle;
    @Column(name = "employer_name", length = 512) private String employerName;
    @Column(name = "job_canonical_url", length = 1024) private String jobCanonicalUrl;
    @Column(name = "job_apply_url", length = 2048) private String jobApplyUrl;
    @Column(name = "job_description_text", columnDefinition = "text") private String jobDescriptionText;

    @Column(name = "letter_text", columnDefinition = "text") private String letterText;

    /** Plain byte[] maps to bytea; @Lob would map to oid (large object). */
    @Column(name = "cv_pdf", nullable = false, columnDefinition = "bytea") private byte[] cvPdf;
    @Column(name = "cv_pdf_sha256", nullable = false, length = 64) private String cvPdfSha256;
    @Column(name = "letter_pdf", nullable = false, columnDefinition = "bytea") private byte[] letterPdf;
    @Column(name = "letter_pdf_sha256", nullable = false, length = 64) private String letterPdfSha256;

    @Column(name = "coverage_percent", nullable = false) private int coveragePercent;
    @Column(name = "rendered_by", length = 128) private String renderedBy;
    @Column(name = "approved_at", nullable = false) private Instant approvedAt;

    public Long getId() { return id; }
    public TailoredApplication getTailoredApplication() { return tailoredApplication; }
    public void setTailoredApplication(TailoredApplication v) { this.tailoredApplication = v; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }
    public String getEmployerName() { return employerName; }
    public void setEmployerName(String employerName) { this.employerName = employerName; }
    public String getJobCanonicalUrl() { return jobCanonicalUrl; }
    public void setJobCanonicalUrl(String v) { this.jobCanonicalUrl = v; }
    public String getJobApplyUrl() { return jobApplyUrl; }
    public void setJobApplyUrl(String jobApplyUrl) { this.jobApplyUrl = jobApplyUrl; }
    public String getJobDescriptionText() { return jobDescriptionText; }
    public void setJobDescriptionText(String v) { this.jobDescriptionText = v; }
    public String getLetterText() { return letterText; }
    public void setLetterText(String letterText) { this.letterText = letterText; }
    public byte[] getCvPdf() { return cvPdf; }
    public void setCvPdf(byte[] cvPdf) { this.cvPdf = cvPdf; }
    public String getCvPdfSha256() { return cvPdfSha256; }
    public void setCvPdfSha256(String v) { this.cvPdfSha256 = v; }
    public byte[] getLetterPdf() { return letterPdf; }
    public void setLetterPdf(byte[] letterPdf) { this.letterPdf = letterPdf; }
    public String getLetterPdfSha256() { return letterPdfSha256; }
    public void setLetterPdfSha256(String v) { this.letterPdfSha256 = v; }
    public int getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(int coveragePercent) { this.coveragePercent = coveragePercent; }
    public String getRenderedBy() { return renderedBy; }
    public void setRenderedBy(String renderedBy) { this.renderedBy = renderedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
