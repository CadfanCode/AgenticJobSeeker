package se.caiowain.jobseeker.ingest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Layer 2 and layer 3 of the three-layer identity scheme from the spec.
 *
 * <p>A plain hash of the job URL is deliberately NOT used: the same ad is served under
 * different URLs by JobTech, by aggregators, and by the employer's own ATS domain.
 */
public final class JobIdentity {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "promotion", "utm_source", "utm_medium", "utm_campaign", "utm_term",
            "utm_content", "fbclid", "gclid", "msclkid", "ref", "source",
            "sessionid", "jsessionid");

    private JobIdentity() {
    }

    /** Normalizes a URL so the same job under cosmetic URL variations compares equal. */
    public static String canonicalUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return url;
            }
            String host = uri.getHost().toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = cleanQuery(uri.getQuery());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
            return scheme + "://" + host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String cleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> kept = Arrays.stream(query.split("&"))
                .filter(p -> !p.isBlank())
                .filter(p -> !TRACKING_PARAMS.contains(
                        p.contains("=") ? p.substring(0, p.indexOf('=')).toLowerCase()
                                        : p.toLowerCase()))
                .sorted()
                .collect(Collectors.toList());
        return String.join("&", kept);
    }

    /** Lowercases, strips punctuation, collapses whitespace. */
    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.toLowerCase()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Layer 3: content fingerprint. This is what recognizes the same job arriving
     * from JobTech and from the employer's own Teamtailor feed.
     */
    public static String fingerprint(String employerOrgNumber, String title, String description) {
        String normalizedDescription = description == null ? "" : description;
        normalizedDescription = normalizedDescription
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        if (normalizedDescription.length() > 512) {
            normalizedDescription = normalizedDescription.substring(0, 512);
        }
        String payload = (employerOrgNumber == null ? "" : employerOrgNumber.trim())
                + "|" + normalizeTitle(title)
                + "|" + normalizedDescription;
        return sha256Hex(payload);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
