package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "job_posting_source")
public class JobPostingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SourceId source;

    @Column(name = "source_ad_id", nullable = false, length = 256)
    private String sourceAdId;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public SourceId getSource() { return source; }
    public void setSource(SourceId source) { this.source = source; }
    public String getSourceAdId() { return sourceAdId; }
    public void setSourceAdId(String sourceAdId) { this.sourceAdId = sourceAdId; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
