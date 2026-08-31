package se.caiowain.jobseeker.profile;

/** Work that reads the CV profile needs one that has been approved. Maps to HTTP 409. */
public class ProfileNotReadyException extends RuntimeException {
    public ProfileNotReadyException(String message) { super(message); }
}
