package se.caiowain.jobseeker.archive;

/**
 * Approval is the freeze point, so it may happen exactly once. Slice 2b's {@code approve}
 * accepted any status and simply set a flag; approving twice now would render and archive a
 * second time. Maps to HTTP 409.
 */
public class ApplicationNotDraftException extends RuntimeException {
    public ApplicationNotDraftException(String message) {
        super(message);
    }
}
