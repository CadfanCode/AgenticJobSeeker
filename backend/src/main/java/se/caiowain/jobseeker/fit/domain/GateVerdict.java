package se.caiowain.jobseeker.fit.domain;

/**
 * A gate's answer.
 *
 * <p>{@link #UNKNOWN} is not a pass. Pattern lists are never complete, and a gate that
 * silently passes everything it failed to parse is worse than no gate — it converts an
 * absence of information into a reassurance.
 */
public enum GateVerdict {
    PASS, FLAG, FAIL, UNKNOWN
}
