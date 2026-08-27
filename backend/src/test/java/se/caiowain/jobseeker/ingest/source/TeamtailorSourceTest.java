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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TeamtailorSourceTest extends AbstractIntegrationTest {

    @Autowired FeedClient feedClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired AtsTenantRepository tenants;
    @Autowired TenantRegistryService registry;

    private WireMockServer server;
    private TeamtailorSource source;

    @BeforeEach
    void setUp() throws Exception {
        tenants.deleteAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String body = new String(getClass().getResourceAsStream("/fixtures/teamtailor-jobs.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        server.stubFor(get(urlPathEqualTo("/jobs.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/feed+json")
                        .withHeader("ETag", "\"tt-1\"")
                        .withBody(body)));

        AtsTenant tenant = new AtsTenant();
        tenant.setVendor(AtsVendor.TEAMTAILOR);
        tenant.setHost("localhost");
        tenant.setFeedUrl("http://localhost:" + server.port() + "/jobs.json");
        tenant.setDiscoveredFrom(SourceId.JOBTECH);
        tenant.setActive(true);
        tenants.save(tenant);

        source = new TeamtailorSource(feedClient, objectMapper, tenants, registry);
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
        assertThat(source.id()).isEqualTo(SourceId.TEAMTAILOR);
    }

    @Test
    void mapsFeedItemsIntoRawJobs() {
        List<RawJob> jobs = source.fetch(List.of(all()));

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.getFirst();
        assertThat(first.sourceAdId()).isEqualTo("49f687e1-ceb1-4701-a5a0-14baf0f77d1d");
        assertThat(first.title()).isEqualTo("Senior Fullstackutvecklare Java/Angular");
        assertThat(first.employerName()).isEqualTo("Avaron AB");
        assertThat(first.municipality()).isEqualTo("Norrkoping");
        assertThat(first.applyUrl()).endsWith("/applications/new");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.deadlineAt()).isNotNull();
    }

    @Test
    void stripsHtmlFromDescription() {
        RawJob first = source.fetch(List.of(all())).getFirst();
        assertThat(first.description()).doesNotContain("<h3>", "<p>");
        assertThat(first.description()).contains("Hos Avaron far du tryggheten");
    }

    @Test
    void filtersLocallyBecauseTheFeedHasNoQueryParameter() {
        List<RawJob> jobs = source.fetch(List.of(
                new SearchCriteriaSpec("java", "java", List.of(), List.of(), List.of())));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().title()).contains("Java");
    }

    @Test
    void storesEtagForConditionalGet() {
        source.fetch(List.of(all()));
        assertThat(tenants.findByHost("localhost").orElseThrow().getEtag()).isEqualTo("\"tt-1\"");
    }

    @Test
    void skipsInactiveTenants() {
        AtsTenant tenant = tenants.findByHost("localhost").orElseThrow();
        tenant.setActive(false);
        tenants.save(tenant);

        assertThat(source.fetch(List.of(all()))).isEmpty();
    }

    @Test
    void aFailingTenantDoesNotAbortTheWholeFetch() {
        AtsTenant broken = new AtsTenant();
        broken.setVendor(AtsVendor.TEAMTAILOR);
        broken.setHost("broken.invalid");
        broken.setFeedUrl("http://broken.invalid:1/jobs.json");
        broken.setDiscoveredFrom(SourceId.JOBTECH);
        broken.setActive(true);
        tenants.save(broken);

        assertThat(source.fetch(List.of(all()))).hasSize(2);
        assertThat(tenants.findByHost("broken.invalid").orElseThrow().getFailureCount())
                .isGreaterThan(0);
    }
}
