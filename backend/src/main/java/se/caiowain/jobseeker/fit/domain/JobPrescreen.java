package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.time.Instant;

/**
 * The cheap, corpus-wide ranking signal for one posting.
 *
 * <p><b>Disposable.</b> Every prescreen run truncates this table and rebuilds it. Nothing
 * that must survive — a triage decision, a deep score — may hold a foreign key into it.
 *
 * <p>{@code matchedSkillCount} is a count of facts, not a score: it is how many of your own
 * skill rows this ad names. There is deliberately no synthetic 0–100 here.
 */
@Entity
@Table(name = "job_prescreen")
public class JobPrescreen {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false, unique = true)
    private JobPosting jobPosting;

    /** Which profile produced this row. A stamp, not part of its identity. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(name = "matched_skill_count", nullable = false)
    private int matchedSkillCount;

    /** Comma-separated names, for display. The count is what orders the list. */
    @Column(name = "matched_skills", columnDefinition = "text")
    private String matchedSkills;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_gate", nullable = false, length = 16)
    private GateVerdict languageGate = GateVerdict.UNKNOWN;

    /** The phrase from the ad behind {@code languageGate}. A veto shows its evidence. */
    @Column(name = "language_note", columnDefinition = "text")
    private String languageNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_gate", nullable = false, length = 16)
    private GateVerdict locationGate = GateVerdict.UNKNOWN;

    @Column(name = "deadline_passed", nullable = false)
    private boolean deadlinePassed;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public int getMatchedSkillCount() { return matchedSkillCount; }
    public void setMatchedSkillCount(int v) { this.matchedSkillCount = v; }
    public String getMatchedSkills() { return matchedSkills; }
    public void setMatchedSkills(String matchedSkills) { this.matchedSkills = matchedSkills; }
    public GateVerdict getLanguageGate() { return languageGate; }
    public void setLanguageGate(GateVerdict languageGate) { this.languageGate = languageGate; }
    public String getLanguageNote() { return languageNote; }
    public void setLanguageNote(String languageNote) { this.languageNote = languageNote; }
    public GateVerdict getLocationGate() { return locationGate; }
    public void setLocationGate(GateVerdict locationGate) { this.locationGate = locationGate; }
    public boolean isDeadlinePassed() { return deadlinePassed; }
    public void setDeadlinePassed(boolean deadlinePassed) { this.deadlinePassed = deadlinePassed; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
