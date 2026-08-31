package se.caiowain.jobseeker.select;

/** No local model is configured or reachable. Maps to HTTP 503. */
public class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(String message) { super(message); }
}
