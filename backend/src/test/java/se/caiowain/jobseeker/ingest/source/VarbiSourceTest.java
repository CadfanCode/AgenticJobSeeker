package se.caiowain.jobseeker.ingest.source;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.RawJob;
import se.caiowain.jobseeker.ingest.SearchCriteriaSpec;
import se.caiowain.jobseeker.ingest.TenantRegistryService;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VarbiSourceTest extends AbstractIntegrationTest {

    @Autowired FeedClient feedClient;
    @Autowired AtsTenantRepository tenants;
    @Autowired TenantRegistryService registry;

    private WireMockServer server;
    private VarbiSource source;

    @BeforeEach
    void setUp() throws Exception {
        tenants.deleteAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String body = new String(getClass().getResourceAsStream("/fixtures/varbi-feed.xml")
                .readAllBytes(), StandardCharsets.UTF_8);
        server.stubFor(get(urlPathEqualTo("/what:rssfeed/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(body)));

        AtsTenant tenant = new AtsTenant();
        tenant.setVendor(AtsVendor.VARBI);
        tenant.setHost("localhost");
        tenant.setFeedUrl("http://localhost:" + server.port() + "/what:rssfeed/");
        tenant.setDiscoveredFrom(SourceId.JOBTECH);
        tenant.setActive(true);
        tenants.save(tenant);

        source = new VarbiSource(feedClient, tenants, registry);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private SearchCriteriaSpec all() {
        return new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of());
    }

    @Test
    void reportsItsSourceId() {
        assertThat(source.id()).isEqualTo(SourceId.VARBI);
    }

    @Test
    void parsesRssItemsIntoRawJobs() {
        List<RawJob> jobs = source.fetch(List.of(all()));

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.getFirst();
        assertThat(first.title()).isEqualTo("Portfolj- och modellansvarig ITSM, Norrkoping");
        assertThat(first.sourceUrl()).isEqualTo("https://transportstyrelsen.varbi.com/en/what:job/jobID:962603/");
        assertThat(first.description()).contains("Vi far samhallet att fungera");
        assertThat(first.publishedAt()).isNotNull();
    }

    @Test
    void derivesStableSourceAdIdFromJobId() {
        RawJob first = source.fetch(List.of(all())).getFirst();
        assertThat(first.sourceAdId()).isEqualTo("962603");
    }

    @Test
    void filtersLocally() {
        List<RawJob> jobs = source.fetch(List.of(
                new SearchCriteriaSpec("dev", "systemutvecklare", List.of(), List.of(), List.of())));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().title()).contains("ITSM");
    }

    @Test
    void aFailingTenantIsRecordedNotThrown() {
        AtsTenant broken = new AtsTenant();
        broken.setVendor(AtsVendor.VARBI);
        broken.setHost("broken.invalid");
        broken.setFeedUrl("http://broken.invalid:1/what:rssfeed/");
        broken.setDiscoveredFrom(SourceId.JOBTECH);
        broken.setActive(true);
        tenants.save(broken);

        assertThat(source.fetch(List.of(all()))).hasSize(2);
        assertThat(tenants.findByHost("broken.invalid").orElseThrow().getFailureCount())
                .isGreaterThan(0);
    }
}
