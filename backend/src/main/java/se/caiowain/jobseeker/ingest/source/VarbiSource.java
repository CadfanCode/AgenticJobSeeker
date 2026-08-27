package se.caiowain.jobseeker.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Varbi publishes RSS 2.0 per tenant at {@code /what:rssfeed/}. Heavily used by Swedish
 * public-sector employers. Parsed with the JDK XML parser to avoid another dependency.
 */
@Component
public class VarbiSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(VarbiSource.class);
    private static final Pattern JOB_ID = Pattern.compile("jobID:(\\d+)");

    private final FeedClient http;
    private final AtsTenantRepository tenants;
    private final TenantRegistryService registry;

    public VarbiSource(FeedClient http, AtsTenantRepository tenants, TenantRegistryService registry) {
        this.http = http;
        this.tenants = tenants;
        this.registry = registry;
    }

    @Override
    public SourceId id() {
        return SourceId.VARBI;
    }

    @Override
    public List<RawJob> fetch(List<SearchCriteriaSpec> criteria) {
        List<RawJob> results = new ArrayList<>();

        for (AtsTenant tenant : tenants.findByVendorAndActiveTrue(AtsVendor.VARBI)) {
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

                for (RawJob job : parse(response.body())) {
                    if (criteria.isEmpty() || criteria.stream().anyMatch(c -> c.matchesLocally(job))) {
                        results.add(job);
                    }
                }
                registry.recordSuccess(tenant, response.etag());
            } catch (Exception e) {
                log.debug("Varbi tenant {} failed: {}", tenant.getHost(), e.getMessage());
                registry.recordFailure(tenant);
            }
        }
        return results;
    }

    private List<RawJob> parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden the parser: these feeds are third-party input.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        NodeList items = document.getElementsByTagName("item");
        List<RawJob> jobs = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String link = tag(item, "link");
            jobs.add(new RawJob(
                    jobIdFrom(link, tag(item, "guid")),
                    link,
                    tag(item, "title"),
                    null,
                    null,
                    null,
                    tag(item, "description"),
                    "sv",
                    link,
                    parseRfc1123(tag(item, "pubDate")),
                    null,
                    "{}"));
        }
        return jobs;
    }

    /** Varbi URLs embed a stable numeric job id: .../what:job/jobID:962603/ */
    private static String jobIdFrom(String link, String guid) {
        for (String candidate : new String[]{link, guid}) {
            if (candidate == null) {
                continue;
            }
            Matcher matcher = JOB_ID.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return guid != null ? guid : link;
    }

    private static String tag(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    private static Instant parseRfc1123(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
