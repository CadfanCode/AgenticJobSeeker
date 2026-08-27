package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "ats_tenant")
public class AtsTenant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AtsVendor vendor;

    @Column(nullable = false, unique = true, length = 255)
    private String host;

    @Column(name = "feed_url", nullable = false, length = 1024)
    private String feedUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "discovered_from", nullable = false, length = 32)
    private SourceId discoveredFrom;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(length = 255)
    private String etag;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    public Long getId() { return id; }
    public AtsVendor getVendor() { return vendor; }
    public void setVendor(AtsVendor vendor) { this.vendor = vendor; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getFeedUrl() { return feedUrl; }
    public void setFeedUrl(String feedUrl) { this.feedUrl = feedUrl; }
    public SourceId getDiscoveredFrom() { return discoveredFrom; }
    public void setDiscoveredFrom(SourceId v) { this.discoveredFrom = v; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public void setLastPolledAt(Instant v) { this.lastPolledAt = v; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
}
