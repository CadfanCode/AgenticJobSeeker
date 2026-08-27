package se.caiowain.jobseeker.ingest;

import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.domain.AtsVendor;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps a job's apply URL onto the ATS vendor behind it, and — for vendors that publish
 * an enumerable feed — onto that feed's URL.
 *
 * <p>Vendor shares measured across 2,000 Swedish IT ads: Teamtailor 29.6%, Varbi 6.0%,
 * ReachMee 5.5%, Recman 4.7%. Only Teamtailor and Varbi expose public feeds.
 */
@Component
public class AtsVendorClassifier {

    /** Host substrings that identify a vendor outright. */
    private static final Map<String, AtsVendor> HOST_MARKERS = new LinkedHashMap<>();

    static {
        HOST_MARKERS.put("teamtailor.com", AtsVendor.TEAMTAILOR);
        HOST_MARKERS.put("varbi.com", AtsVendor.VARBI);
        HOST_MARKERS.put("reachmee.com", AtsVendor.REACHMEE);
        HOST_MARKERS.put("recman.", AtsVendor.RECMAN);
        HOST_MARKERS.put("talentech.io", AtsVendor.TALENTECH);
        HOST_MARKERS.put("smartrecruiters.com", AtsVendor.SMARTRECRUITERS);
        HOST_MARKERS.put("myworkdayjobs.com", AtsVendor.WORKDAY);
        HOST_MARKERS.put("ashbyhq.com", AtsVendor.ASHBY);
        HOST_MARKERS.put("lever.co", AtsVendor.LEVER);
        HOST_MARKERS.put("greenhouse.io", AtsVendor.GREENHOUSE);
        HOST_MARKERS.put("jobylon.com", AtsVendor.JOBYLON);
    }

    /** Teamtailor's signature path shape, used by tenants on custom domains. */
    private static final Pattern TEAMTAILOR_PATH =
            Pattern.compile("/jobs/\\d+-[^/]+(/applications/new)?/?$");

    public AtsVendor classify(String applyUrl, String reference) {
        String blob = ((applyUrl == null ? "" : applyUrl) + " "
                + (reference == null ? "" : reference)).toLowerCase(Locale.ROOT);

        for (Map.Entry<String, AtsVendor> entry : HOST_MARKERS.entrySet()) {
            if (blob.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (blob.contains("teamtailor")) {
            return AtsVendor.TEAMTAILOR;
        }
        if (applyUrl != null) {
            try {
                URI uri = URI.create(applyUrl);
                if (uri.getPath() != null && TEAMTAILOR_PATH.matcher(uri.getPath()).find()) {
                    return AtsVendor.TEAMTAILOR;
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to OTHER
            }
        }
        return AtsVendor.OTHER;
    }

    /** Only vendors with an enumerable public feed return a value. */
    public Optional<String> feedUrlFor(AtsVendor vendor, String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        return switch (vendor) {
            case TEAMTAILOR -> Optional.of("https://" + host + "/jobs.json");
            case VARBI -> Optional.of("https://" + host + "/what:rssfeed/");
            default -> Optional.empty();
        };
    }

    public Optional<String> hostOf(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return Optional.empty();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return Optional.of(host.startsWith("www.") ? host.substring(4) : host);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
