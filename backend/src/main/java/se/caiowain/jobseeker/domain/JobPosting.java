package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "job_posting")
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String fingerprint;

    @Column(name = "canonical_url", nullable = false, length = 1024)
    private String canonicalUrl;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "employer_name", length = 512)
    private String employerName;

    @Column(name = "employer_org_number", length = 32)
    private String employerOrgNumber;

    @Column(length = 128)
    private String municipality;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 8)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_vendor", nullable = false, length = 32)
    private AtsVendor atsVendor = AtsVendor.OTHER;

    @Column(name = "apply_url", length = 2048)
    private String applyUrl;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status = JobStatus.DISCOVERED;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @OneToMany(mappedBy = "jobPosting", fetch = FetchType.LAZY)
    private List<JobPostingSource> sources = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEmployerName() { return employerName; }
    public void setEmployerName(String employerName) { this.employerName = employerName; }
    public String getEmployerOrgNumber() { return employerOrgNumber; }
    public void setEmployerOrgNumber(String v) { this.employerOrgNumber = v; }
    public String getMunicipality() { return municipality; }
    public void setMunicipality(String municipality) { this.municipality = municipality; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public AtsVendor getAtsVendor() { return atsVendor; }
    public void setAtsVendor(AtsVendor atsVendor) { this.atsVendor = atsVendor; }
    public String getApplyUrl() { return applyUrl; }
    public void setApplyUrl(String applyUrl) { this.applyUrl = applyUrl; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public List<JobPostingSource> getSources() { return sources; }
}
