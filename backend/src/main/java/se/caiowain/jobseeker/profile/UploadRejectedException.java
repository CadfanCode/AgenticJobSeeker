package se.caiowain.jobseeker.profile;

/** The uploaded file is not something we accept. Maps to HTTP 400. */
public class UploadRejectedException extends RuntimeException {
    public UploadRejectedException(String message) {
        super(message);
    }
}
