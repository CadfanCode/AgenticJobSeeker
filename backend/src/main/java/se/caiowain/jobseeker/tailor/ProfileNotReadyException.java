package se.caiowain.jobseeker.tailor;

/** Tailoring needs an approved CV profile. Maps to HTTP 409. */
public class ProfileNotReadyException extends RuntimeException {
    public ProfileNotReadyException(String message) { super(message); }
}
