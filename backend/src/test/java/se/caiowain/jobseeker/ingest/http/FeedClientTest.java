package se.caiowain.jobseeker.ingest.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FeedClientTest {

    private WireMockServer server;
    private FeedClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = new FeedClient("TestAgent/1.0", 10, 0);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    @Test
    void fetchesBodyAndEtag() {
        server.stubFor(get(urlEqualTo("/jobs.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"v1\"")
                        .withBody("{\"items\":[]}")));

        FeedResponse response = client.get(url("/jobs.json"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"items\":[]}");
        assertThat(response.etag()).isEqualTo("\"v1\"");
        assertThat(response.notModified()).isFalse();
    }

    @Test
    void sendsIfNoneMatchAndReportsNotModified() {
        server.stubFor(get(urlEqualTo("/jobs.json"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        FeedResponse response = client.get(url("/jobs.json"), "\"v1\"");

        assertThat(response.notModified()).isTrue();
        assertThat(response.status()).isEqualTo(304);
    }

    @Test
    void sendsConfiguredUserAgent() {
        server.stubFor(get(urlEqualTo("/jobs.json"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(url("/jobs.json"));

        server.verify(getRequestedFor(urlEqualTo("/jobs.json"))
                .withHeader("User-Agent", equalTo("TestAgent/1.0")));
    }

    @Test
    void returnsStatusForNotFoundWithoutThrowing() {
        server.stubFor(get(urlEqualTo("/missing.json"))
                .willReturn(aResponse().withStatus(404).withBody("nope")));

        FeedResponse response = client.get(url("/missing.json"));

        assertThat(response.status()).isEqualTo(404);
    }
}
