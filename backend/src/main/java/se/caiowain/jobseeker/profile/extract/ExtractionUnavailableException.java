package se.caiowain.jobseeker.profile.extract;

/** No model credential is configured, so extraction cannot run. Maps to HTTP 503. */
public class ExtractionUnavailableException extends RuntimeException {
    public ExtractionUnavailableException(String message) {
        super(message);
    }
}
