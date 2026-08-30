package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.fit.domain.LanguageLevel;

/**
 * A language demand read out of an ad.
 *
 * @param phrase the wording that produced this reading, kept so the gate can quote its
 *               own evidence back to the user instead of asserting a verdict
 */
public record LanguageRequirement(String language, LanguageLevel level, String phrase) {
}
