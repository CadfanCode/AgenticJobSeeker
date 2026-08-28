package se.caiowain.jobseeker.tailor.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tailored_application")
public class TailoredApplication {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "coverage_percent", nullable = false) private int coveragePercent;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "letter_prose", columnDefinition = "text") private String letterProse;
    @Column(name = "raw_model_output", columnDefinition = "text") private String rawModelOutput;

    @OneToMany(mappedBy = "tailoredApplication", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<ApplicationRequirement> requirements = new ArrayList<>();

    public void addRequirement(ApplicationRequirement r) {
        r.setTailoredApplication(this);
        requirements.add(r);
    }

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public int getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(int coveragePercent) { this.coveragePercent = coveragePercent; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getLetterProse() { return letterProse; }
    public void setLetterProse(String letterProse) { this.letterProse = letterProse; }
    public String getRawModelOutput() { return rawModelOutput; }
    public void setRawModelOutput(String rawModelOutput) { this.rawModelOutput = rawModelOutput; }
    public List<ApplicationRequirement> getRequirements() { return requirements; }
}
