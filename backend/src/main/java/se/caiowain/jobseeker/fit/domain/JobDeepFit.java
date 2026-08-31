package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One measured job, from roughly 94 seconds of local-model time.
 *
 * <p><b>Preserved.</b> Keyed on (posting, profile) so a superseded profile's scores stay as
 * history rather than being silently overwritten by a CV re-upload.
 *
 * <p>{@code coveragePercent} is computed in Java by {@code Coverage}, from counted rows. It
 * is not a model's opinion and not a fit score in the judgemental sense — it is the fraction
 * of the ad's stated requirements that your own bullets answer.
 */
@Entity
@Table(name = "job_deep_fit")
public class JobDeepFit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(name = "coverage_percent", nullable = false) private int coveragePercent;
    @Column(name = "requirement_count", nullable = false) private int requirementCount;
    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "deep_scored_at", nullable = false) private Instant deepScoredAt;

    @OneToMany(mappedBy = "deepFit", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<JobDeepFitGap> gaps = new ArrayList<>();

    public void addGap(JobDeepFitGap gap) {
        gap.setDeepFit(this);
        gaps.add(gap);
    }

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public int getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(int v) { this.coveragePercent = v; }
    public int getRequirementCount() { return requirementCount; }
    public void setRequirementCount(int v) { this.requirementCount = v; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public Instant getDeepScoredAt() { return deepScoredAt; }
    public void setDeepScoredAt(Instant deepScoredAt) { this.deepScoredAt = deepScoredAt; }
    public List<JobDeepFitGap> getGaps() { return gaps; }
}
