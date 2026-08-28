package se.caiowain.jobseeker.tailor;

/** No local model is configured or reachable. Maps to HTTP 503. */
public class TailoringUnavailableException extends RuntimeException {
    public TailoringUnavailableException(String message) { super(message); }
}
