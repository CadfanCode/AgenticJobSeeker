package se.caiowain.jobseeker.fit.domain;

public enum RemotePolicy {
    /** Must be within reach of the office; the municipality list is a hard filter. */
    ONSITE_ONLY,
    /** Same location rule as onsite — you still have to reach the office some days. */
    HYBRID_OK,
    /** Location is irrelevant, so the location gate always passes. */
    REMOTE_ONLY
}
