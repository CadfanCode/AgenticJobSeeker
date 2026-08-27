package se.caiowain.jobseeker.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * The self-expanding tenant registry. JobTech ingestion feeds apply URLs in here;
 * every host that resolves to a vendor with a public feed becomes a pollable tenant.
 * No tenant list is ever configured by hand.
 */
@Service
public class TenantRegistryService {

    private final AtsTenantRepository tenants;
    private final AtsVendorClassifier classifier;
    private final int maxFailures;

    public TenantRegistryService(AtsTenantRepository tenants,
                                 AtsVendorClassifier classifier,
                                 @Value("${jobseeker.ingest.max-tenant-failures:5}") int maxFailures) {
        this.tenants = tenants;
        this.classifier = classifier;
        this.maxFailures = maxFailures;
    }

    @Transactional
    public Optional<AtsTenant> registerFromApplyUrl(String applyUrl, String reference, SourceId discoveredFrom) {
        AtsVendor vendor = classifier.classify(applyUrl, reference);
        Optional<String> host = classifier.hostOf(applyUrl);
        if (host.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> feedUrl = classifier.feedUrlFor(vendor, host.get());
        if (feedUrl.isEmpty()) {
            return Optional.empty();
        }

        Optional<AtsTenant> existing = tenants.findByHost(host.get());
        if (existing.isPresent()) {
            return existing;
        }

        AtsTenant tenant = new AtsTenant();
        tenant.setVendor(vendor);
        tenant.setHost(host.get());
        tenant.setFeedUrl(feedUrl.get());
        tenant.setDiscoveredFrom(discoveredFrom);
        tenant.setActive(true);
        return Optional.of(tenants.save(tenant));
    }

    @Transactional
    public void recordSuccess(AtsTenant tenant, String etag) {
        AtsTenant managed = tenants.findById(tenant.getId()).orElse(tenant);
        managed.setFailureCount(0);
        managed.setLastPolledAt(Instant.now());
        if (etag != null && !etag.isBlank()) {
            managed.setEtag(etag);
        }
        tenants.save(managed);
    }

    /** A tenant that never had a feed is normal, not exceptional: deactivate, do not retry forever. */
    @Transactional
    public void recordFailure(AtsTenant tenant) {
        AtsTenant managed = tenants.findById(tenant.getId()).orElse(tenant);
        managed.setFailureCount(managed.getFailureCount() + 1);
        managed.setLastPolledAt(Instant.now());
        if (managed.getFailureCount() >= maxFailures) {
            managed.setActive(false);
        }
        tenants.save(managed);
    }
}
