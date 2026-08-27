package se.caiowain.jobseeker.ingest.source;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
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
class JobTechSourceTest extends AbstractIntegrationTest {

    @Autowired FeedClient feedClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRegistryService registry;
    @Autowired AtsTenantRepository tenants;

    private WireMockServer server;
    private JobTechSource source;

    @BeforeEach
    void setUp() throws Exception {
        tenants.deleteAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String body = new String(getClass().getResourceAsStream("/fixtures/jobtech-search.json")
                .readAllBytes(), StandardCharsets.UTF_8);

        server.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        source = new JobTechSource(feedClient, objectMapper, registry,
                "http://localhost:" + server.port(), 100, 2000);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void reportsItsSourceId() {
        assertThat(source.id()).isEqualTo(SourceId.JOBTECH);
    }

    @Test
    void mapsHitsIntoRawJobs() {
        List<RawJob> jobs = source.fetch(List.of(
                new SearchCriteriaSpec("java", "java", List.of(), List.of(), List.of())));

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.getFirst();
        assertThat(first.sourceAdId()).isEqualTo("31404250");
        assertThat(first.title()).isEqualTo("Senior Fullstackutvecklare Java/Angular");
        assertThat(first.employerName()).isEqualTo("Avaron AB");
        assertThat(first.employerOrgNumber()).isEqualTo("5591754279");
        assertThat(first.municipality()).isEqualTo("Norrkoping");
        assertThat(first.sourceUrl()).isEqualTo("https://arbetsformedlingen.se/platsbanken/annonser/31404250");
        assertThat(first.applyUrl()).contains("jobs.avaron.se");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.rawPayload()).contains("31404250");
    }

    @Test
    void sendsCriteriaAsQueryParameters() {
        source.fetch(List.of(new SearchCriteriaSpec(
                "sthlm", "java utvecklare", List.of("AvNB_uwa_6n6"), List.of(), List.of("apaJ_2ja_LuF"))));

        server.verify(getRequestedFor(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("java utvecklare"))
                .withQueryParam("municipality", equalTo("AvNB_uwa_6n6"))
                .withQueryParam("occupation-field", equalTo("apaJ_2ja_LuF")));
    }

    @Test
    void seedsTenantRegistryFromApplyUrls() {
        source.fetch(List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of())));

        assertThat(tenants.findByHost("jobs.avaron.se")).isPresent();
        assertThat(tenants.findByHost("jobs.avaron.se").orElseThrow().getVendor())
                .isEqualTo(AtsVendor.TEAMTAILOR);
        assertThat(tenants.findByHost("transportstyrelsen.varbi.com")).isPresent();
        assertThat(tenants.findByHost("transportstyrelsen.varbi.com").orElseThrow().getVendor())
                .isEqualTo(AtsVendor.VARBI);
    }

    @Test
    void neverRequestsBeyondTheOffsetCeiling() {
        source.fetch(List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of())));

        server.getAllServeEvents().forEach(event -> {
            String offset = event.getRequest().queryParameter("offset").firstValue();
            assertThat(Integer.parseInt(offset)).isLessThanOrEqualTo(2000);
        });
    }
}
