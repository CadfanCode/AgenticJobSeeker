package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;

/**
 * A requirement the ad states that nothing in the CV answers.
 *
 * <p>The text is a phrase quoted verbatim from the ad — Guard B enforces that — so it is
 * the employer's words, never a claim about the candidate. These rows are the most useful
 * output of a deep score: they name the honest distance between you and the job.
 */
@Entity
@Table(name = "job_deep_fit_gap")
public class JobDeepFitGap {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_deep_fit_id", nullable = false)
    private JobDeepFit deepFit;

    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public JobDeepFit getDeepFit() { return deepFit; }
    public void setDeepFit(JobDeepFit deepFit) { this.deepFit = deepFit; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
