package se.caiowain.jobseeker.ingest.http;

public record FeedResponse(int status, String body, String etag, boolean notModified) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
