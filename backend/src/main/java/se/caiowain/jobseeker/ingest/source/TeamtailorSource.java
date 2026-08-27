package se.caiowain.jobseeker.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.JobSource;
import se.caiowain.jobseeker.ingest.RawJob;
import se.caiowain.jobseeker.ingest.SearchCriteriaSpec;
import se.caiowain.jobseeker.ingest.TenantRegistryService;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.ingest.http.FeedResponse;
import se.caiowain.jobseeker.repo.AtsTenantRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the public JSON Feed that every Teamtailor career site publishes at
 * {@code /jobs.json}. Keyless, and the {@code _jobposting} extension carries
 * schema.org JobPosting data richer than the JobTech listing for the same ad.
 *
 * <p>The feed is whole-catalogue, so search criteria are applied client-side.
 */
@Component
public class TeamtailorSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(TeamtailorSource.class);

    private final FeedClient http;
    private final ObjectMapper mapper;
    private final AtsTenantRepository tenants;
    private final TenantRegistryService registry;

    public TeamtailorSource(FeedClient http, ObjectMapper mapper,
                            AtsTenantRepository tenants, TenantRegistryService registry) {
        this.http = http;
        this.mapper = mapper;
        this.tenants = tenants;
        this.registry = registry;
    }

    @Override
    public SourceId id() {
        return SourceId.TEAMTAILOR;
    }

    @Override
    public List<RawJob> fetch(List<SearchCriteriaSpec> criteria) {
        List<RawJob> results = new ArrayList<>();

        for (AtsTenant tenant : tenants.findByVendorAndActiveTrue(AtsVendor.TEAMTAILOR)) {
            try {
                FeedResponse response = http.get(tenant.getFeedUrl(), tenant.getEtag());
                if (response.notModified()) {
                    registry.recordSuccess(tenant, tenant.getEtag());
                    continue;
                }
                if (!response.isSuccess()) {
                    registry.recordFailure(tenant);
                    continue;
                }

                JsonNode root = mapper.readTree(response.body());
                for (JsonNode item : root.path("items")) {
                    RawJob job = toRawJob(item);
                    if (matchesAny(job, criteria)) {
                        results.add(job);
                    }
                }
                registry.recordSuccess(tenant, response.etag());
            } catch (Exception e) {
                // One bad tenant must never abort the run.
                log.debug("Teamtailor tenant {} failed: {}", tenant.getHost(), e.getMessage());
                registry.recordFailure(tenant);
            }
        }
        return results;
    }

    private boolean matchesAny(RawJob job, List<SearchCriteriaSpec> criteria) {
        return criteria.isEmpty() || criteria.stream().anyMatch(c -> c.matchesLocally(job));
    }

    private RawJob toRawJob(JsonNode item) {
        JsonNode posting = item.path("_jobposting");
        String url = text(item.path("url"));

        return new RawJob(
                text(item.path("id")),
                url,
                text(item.path("title")),
                text(posting.path("hiringOrganization").path("name")),
                null, // Teamtailor feeds do not expose the employer org number
                text(posting.path("jobLocation").path("address").path("addressLocality")),
                stripHtml(text(item.path("content_html"))),
                null,
                url == null ? null : url + "/applications/new",
                parseInstant(text(item.path("date_published"))),
                parseInstant(text(posting.path("validThrough"))),
                item.toString());
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
