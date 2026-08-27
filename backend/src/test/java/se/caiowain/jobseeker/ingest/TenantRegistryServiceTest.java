package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TenantRegistryServiceTest extends AbstractIntegrationTest {

    @Autowired TenantRegistryService registry;
    @Autowired AtsTenantRepository tenants;

    @BeforeEach
    void clean() {
        tenants.deleteAll();
    }

    @Test
    void registersTeamtailorTenantFromApplyUrl() {
        var tenant = registry.registerFromApplyUrl(
                "https://jobs.avaron.se/jobs/8278099-x/applications/new?promotion=1-arbetsformedlingen",
                null, SourceId.JOBTECH);

        assertThat(tenant).isPresent();
        assertThat(tenant.get().getVendor()).isEqualTo(AtsVendor.TEAMTAILOR);
        assertThat(tenant.get().getHost()).isEqualTo("jobs.avaron.se");
        assertThat(tenant.get().getFeedUrl()).isEqualTo("https://jobs.avaron.se/jobs.json");
    }

    @Test
    void registersVarbiTenantFromApplyUrl() {
        var tenant = registry.registerFromApplyUrl(
                "https://transportstyrelsen.varbi.com/se/what:job/jobID:962618/", null, SourceId.JOBTECH);

        assertThat(tenant).isPresent();
        assertThat(tenant.get().getVendor()).isEqualTo(AtsVendor.VARBI);
        assertThat(tenant.get().getFeedUrl()).isEqualTo("https://transportstyrelsen.varbi.com/what:rssfeed/");
    }

    @Test
    void doesNotRegisterVendorsWithoutFeeds() {
        assertThat(registry.registerFromApplyUrl(
                "https://web103.reachmee.com/ext/I003/584/main?site=19", null, SourceId.JOBTECH))
                .isEmpty();
        assertThat(tenants.count()).isZero();
    }

    @Test
    void registrationIsIdempotentPerHost() {
        registry.registerFromApplyUrl("https://jobs.avaron.se/jobs/1-a/applications/new", null, SourceId.JOBTECH);
        registry.registerFromApplyUrl("https://jobs.avaron.se/jobs/2-b/applications/new", null, SourceId.JOBTECH);
        assertThat(tenants.count()).isEqualTo(1);
    }

    @Test
    void deactivatesTenantAfterRepeatedFailures() {
        AtsTenant tenant = registry.registerFromApplyUrl(
                "https://jobs.avaron.se/jobs/1-a/applications/new", null, SourceId.JOBTECH).orElseThrow();

        for (int i = 0; i < 5; i++) {
            registry.recordFailure(tenant);
        }

        assertThat(tenants.findByHost("jobs.avaron.se").orElseThrow().isActive()).isFalse();
        assertThat(tenants.findByVendorAndActiveTrue(AtsVendor.TEAMTAILOR)).isEmpty();
    }

    @Test
    void successResetsFailureCountAndStoresEtag() {
        AtsTenant tenant = registry.registerFromApplyUrl(
                "https://jobs.avaron.se/jobs/1-a/applications/new", null, SourceId.JOBTECH).orElseThrow();
        registry.recordFailure(tenant);
        registry.recordSuccess(tenant, "\"abc123\"");

        AtsTenant reloaded = tenants.findByHost("jobs.avaron.se").orElseThrow();
        assertThat(reloaded.getFailureCount()).isZero();
        assertThat(reloaded.getEtag()).isEqualTo("\"abc123\"");
        assertThat(reloaded.getLastPolledAt()).isNotNull();
    }
}
