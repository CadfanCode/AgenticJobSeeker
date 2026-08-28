package se.caiowain.jobseeker.tailor;

import java.util.List;

/** The model's output failed the guards twice. Maps to HTTP 422. */
public class TailoringRejectedException extends RuntimeException {
    private final List<String> violations;

    public TailoringRejectedException(String message, List<String> violations) {
        super(message);
        this.violations = violations;
    }

    public List<String> getViolations() { return violations; }
}
