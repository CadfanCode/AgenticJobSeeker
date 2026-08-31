package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;

import java.time.Instant;

/**
 * Your decision about a posting.
 *
 * <p><b>Preserved.</b> Nothing recomputes this. It survives prescreen rebuilds, CV
 * re-extraction and ingest runs, because it is a judgement rather than a derivation —
 * which is exactly why it does not live in {@code job_prescreen}.
 */
@Entity
@Table(name = "job_triage")
public class JobTriage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false, unique = true)
    private JobPosting jobPosting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TriageState state = TriageState.NEW;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(columnDefinition = "text")
    private String note;

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public TriageState getState() { return state; }
    public void setState(TriageState state) { this.state = state; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
