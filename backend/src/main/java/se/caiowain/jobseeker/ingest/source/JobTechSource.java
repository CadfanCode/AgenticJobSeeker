package se.caiowain.jobseeker.ingest.source;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.JobSource;
import se.caiowain.jobseeker.ingest.RawJob;
import se.caiowain.jobseeker.ingest.SearchCriteriaSpec;
import se.caiowain.jobseeker.ingest.TenantRegistryService;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.ingest.http.FeedResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Arbetsformedlingen's JobTech Dev API. Keyless, open government data, and the only
 * source in this slice that filters server-side.
 *
 * <p>Its apply URLs are also the seed for {@link TenantRegistryService}: every ad tells
 * us which ATS the employer uses, which is how Teamtailor and Varbi tenants get found.
 */
@Component
public class JobTechSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(JobTechSource.class);

    private final FeedClient http;
    private final ObjectMapper mapper;
    private final TenantRegistryService registry;
    private final String baseUrl;
    private final int pageSize;
    private final int maxOffset;

    public JobTechSource(FeedClient http,
                         ObjectMapper mapper,
                         TenantRegistryService registry,
                         @Value("${jobseeker.sources.jobtech.base-url}") String baseUrl,
                         @Value("${jobseeker.sources.jobtech.page-size:100}") int pageSize,
                         @Value("${jobseeker.sources.jobtech.max-offset:2000}") int maxOffset) {
        this.http = http;
        this.mapper = mapper;
        this.registry = registry;
        this.baseUrl = baseUrl;
        this.pageSize = pageSize;
        this.maxOffset = maxOffset;
    }

    @Override
    public SourceId id() {
        return SourceId.JOBTECH;
    }

    @Override
    public List<RawJob> fetch(List<SearchCriteriaSpec> criteria) {
        List<RawJob> all = new ArrayList<>();
        for (SearchCriteriaSpec spec : criteria) {
            all.addAll(fetchOne(spec));
        }
        return all;
    }

    private List<RawJob> fetchOne(SearchCriteriaSpec spec) {
        List<RawJob> jobs = new ArrayList<>();
        int offset = 0;

        while (offset <= maxOffset) {
            FeedResponse response = http.get(buildUrl(spec, offset));
            if (!response.isSuccess()) {
                log.warn("JobTech returned {} for criteria '{}' at offset {}",
                        response.status(), spec.name(), offset);
                break;
            }

            JsonNode root = readTree(response.body());
            JsonNode hits = root.path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                break;
            }

            for (JsonNode hit : hits) {
                jobs.add(toRawJob(hit));
            }

            int total = root.path("total").path("value").asInt(0);
            offset += pageSize;
            if (offset >= total) {
                break;
            }
            if (offset > maxOffset) {
                log.warn("Criteria '{}' matches {} ads, exceeding the JobTech offset ceiling of {}. "
                        + "Results truncated; narrow the criteria.", spec.name(), total, maxOffset);
                break;
            }
        }
        return jobs;
    }

    private String buildUrl(SearchCriteriaSpec spec, int offset) {
        StringBuilder url = new StringBuilder(baseUrl).append("/search?limit=")
                .append(pageSize).append("&offset=").append(offset);
        if (spec.query() != null && !spec.query().isBlank()) {
            url.append("&q=").append(URLEncoder.encode(spec.query(), StandardCharsets.UTF_8));
        }
        for (String code : spec.municipalityCodes()) {
            url.append("&municipality=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        }
        for (String code : spec.occupationFieldCodes()) {
            url.append("&occupation-field=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private RawJob toRawJob(JsonNode hit) {
        String applyUrl = text(hit.path("application_details").path("url"));
        String reference = text(hit.path("application_details").path("reference"));

        // Seed tenant discovery: this is how Teamtailor and Varbi tenants are found.
        try {
            registry.registerFromApplyUrl(applyUrl, reference, SourceId.JOBTECH);
        } catch (RuntimeException e) {
            log.debug("Tenant registration skipped for {}: {}", applyUrl, e.getMessage());
        }

        return new RawJob(
                text(hit.path("id")),
                text(hit.path("webpage_url")),
                text(hit.path("headline")),
                text(hit.path("employer").path("name")),
                text(hit.path("employer").path("organization_number")),
                text(hit.path("workplace_address").path("municipality")),
                text(hit.path("description").path("text")),
                "sv",
                applyUrl,
                parseInstant(text(hit.path("publication_date"))),
                parseInstant(text(hit.path("application_deadline"))),
                hit.toString());
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed JobTech response", e);
        }
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
