package se.caiowain.jobseeker.ingest.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared HTTP access for feed polling. Adds a contactable User-Agent, conditional GET
 * via ETag, and a per-host delay so that polling hundreds of tenant feeds stays polite.
 */
@Component
public class FeedClient {

    private final HttpClient http;
    private final String userAgent;
    private final Duration timeout;
    private final long perHostDelayMillis;
    private final Map<String, Long> lastRequestByHost = new ConcurrentHashMap<>();

    public FeedClient(@Value("${jobseeker.ingest.user-agent}") String userAgent,
                      @Value("${jobseeker.ingest.request-timeout-seconds:20}") int timeoutSeconds,
                      @Value("${jobseeker.ingest.per-host-delay-millis:500}") long perHostDelayMillis) {
        this.userAgent = userAgent;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.perHostDelayMillis = perHostDelayMillis;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public FeedResponse get(String url) {
        return get(url, null);
    }

    public FeedResponse get(String url, String etag) {
        URI uri = URI.create(url);
        throttle(uri.getHost());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/feed+json, application/json, application/rss+xml, */*");
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }

        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseEtag = response.headers().firstValue("ETag").orElse(null);
            boolean notModified = response.statusCode() == 304;
            return new FeedResponse(response.statusCode(),
                    notModified ? null : response.body(), responseEtag, notModified);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedFetchException("Interrupted fetching " + url, e);
        } catch (Exception e) {
            throw new FeedFetchException("Failed fetching " + url + ": " + e.getMessage(), e);
        }
    }

    /** Keeps at least the configured gap between two requests to the same host. */
    private void throttle(String host) {
        if (host == null || perHostDelayMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastRequestByHost.get(host);
        if (previous != null) {
            long wait = perHostDelayMillis - (now - previous);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastRequestByHost.put(host, System.currentTimeMillis());
    }

    public static class FeedFetchException extends RuntimeException {
        public FeedFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
