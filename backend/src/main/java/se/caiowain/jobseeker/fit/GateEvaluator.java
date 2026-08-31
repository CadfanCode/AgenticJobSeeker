package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns an ad plus your stated preferences into vetoes. Pure: no Spring, no I/O.
 *
 * <p>A gate never removes a posting. It produces a verdict and the phrase behind it, and the
 * job list sorts failures out of the default view — you may know something about your own
 * situation that these preferences do not record, so the verdict has to stay overridable.
 */
public class GateEvaluator {

    private final LanguageRequirementDetector detector;

    public GateEvaluator(LanguageRequirementDetector detector) {
        this.detector = detector;
    }

    public GateOutcome evaluate(JobPosting job, JobPreferences prefs, Instant now) {
        List<LanguageRequirement> required = detector.detect(job.getDescription());

        GateVerdict languageGate = GateVerdict.UNKNOWN;
        String languageNote = null;

        // The worst verdict wins: one language passing cannot cancel another one failing.
        for (LanguageRequirement requirement : required) {
            GateVerdict verdict = judge(requirement, prefs);
            if (severity(verdict) > severity(languageGate)) {
                languageGate = verdict;
                languageNote = requirement.phrase();
            }
        }

        return new GateOutcome(languageGate, languageNote,
                locationGate(job, prefs), deadlinePassed(job, now));
    }

    private GateVerdict judge(LanguageRequirement requirement, JobPreferences prefs) {
        Optional<CandidateLanguage> spoken = prefs.getLanguages().stream()
                .filter(l -> l.getLanguage() != null
                        && l.getLanguage().equalsIgnoreCase(requirement.language()))
                .findFirst();

        if (spoken.isEmpty()) {
            return GateVerdict.FAIL;
        }
        LanguageLevel level = spoken.get().getLevel();
        return level.atLeast(requirement.level()) ? GateVerdict.PASS : GateVerdict.FLAG;
    }

    private GateVerdict locationGate(JobPosting job, JobPreferences prefs) {
        if (prefs.getRemotePolicy() == RemotePolicy.REMOTE_ONLY) {
            return GateVerdict.PASS;
        }
        List<String> acceptable = prefs.acceptableMunicipalityList();
        if (acceptable.isEmpty()) {
            return GateVerdict.PASS;
        }
        String municipality = job.getMunicipality();
        if (municipality == null || municipality.isBlank()) {
            return GateVerdict.UNKNOWN;
        }
        return acceptable.stream().anyMatch(municipality::equalsIgnoreCase)
                ? GateVerdict.PASS
                : GateVerdict.FAIL;
    }

    private boolean deadlinePassed(JobPosting job, Instant now) {
        return job.getDeadlineAt() != null && job.getDeadlineAt().isBefore(now);
    }

    /** Severity order: FAIL beats FLAG beats PASS beats UNKNOWN. */
    private static int severity(GateVerdict verdict) {
        return switch (verdict) {
            case UNKNOWN -> 0;
            case PASS -> 1;
            case FLAG -> 2;
            case FAIL -> 3;
        };
    }
}
