package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.fit.domain.GateVerdict;

/**
 * @param languageNote the phrase from the ad behind {@code languageGate}, or null when
 *                     nothing was recognised. A gate always shows its own evidence.
 */
public record GateOutcome(GateVerdict languageGate, String languageNote,
                          GateVerdict locationGate, boolean deadlinePassed) {
}
