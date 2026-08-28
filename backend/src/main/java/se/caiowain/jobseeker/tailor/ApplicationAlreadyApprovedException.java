package se.caiowain.jobseeker.tailor;

/**
 * Re-tailoring would destroy an approved application. Maps to HTTP 409.
 *
 * <p>A dedicated type rather than IllegalStateException: Slice 2a's advice also handles
 * IllegalStateException, and two @RestControllerAdvice beans claiming one exception type
 * resolve in unspecified order. Specific exceptions cannot collide.
 */
public class ApplicationAlreadyApprovedException extends RuntimeException {
    public ApplicationAlreadyApprovedException(String message) { super(message); }
}
