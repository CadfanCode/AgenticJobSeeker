package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.fit.domain.LanguageLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads language demands out of a job ad. Pure: no Spring, no I/O.
 *
 * <p>Deliberately a phrase list rather than a model call. The vocabulary Swedish IT ads use
 * is small and stable, a list is auditable line by line, and it costs nothing to run across
 * the whole corpus. Its incompleteness is handled honestly rather than hidden: an ad this
 * list does not recognise produces no requirement, and the gate turns that into UNKNOWN.
 *
 * <p>Ordering is load-bearing. Patterns are tried in order and the first hit per language
 * wins, so a phrase that contains another must come first — otherwise "mycket goda kunskaper
 * i svenska" would be read as the weaker "goda kunskaper i svenska".
 */
public class LanguageRequirementDetector {

    private record Phrase(String text, String language, LanguageLevel level) {
    }

    private static final List<Phrase> PATTERNS = List.of(
            // Swedish, strongest first.
            new Phrase("svenska på modersmålsnivå", "sv", LanguageLevel.NATIVE),
            new Phrase("mycket goda kunskaper i svenska", "sv", LanguageLevel.FLUENT),
            new Phrase("flytande svenska", "sv", LanguageLevel.FLUENT),
            new Phrase("svenska flytande", "sv", LanguageLevel.FLUENT),
            new Phrase("obehindrat på svenska", "sv", LanguageLevel.FLUENT),
            new Phrase("fluent swedish", "sv", LanguageLevel.FLUENT),
            new Phrase("fluent in swedish", "sv", LanguageLevel.FLUENT),
            new Phrase("svenska i tal och skrift", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("goda kunskaper i svenska", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("behärskar svenska", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("professional swedish", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("grundläggande svenska", "sv", LanguageLevel.BASIC),

            // English, strongest first.
            new Phrase("mycket goda kunskaper i engelska", "en", LanguageLevel.FLUENT),
            new Phrase("flytande engelska", "en", LanguageLevel.FLUENT),
            new Phrase("obehindrat på engelska", "en", LanguageLevel.FLUENT),
            new Phrase("fluent english", "en", LanguageLevel.FLUENT),
            new Phrase("fluent in english", "en", LanguageLevel.FLUENT),
            new Phrase("engelska i tal och skrift", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("goda kunskaper i engelska", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("behärskar engelska", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("professional english", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("grundläggande engelska", "en", LanguageLevel.BASIC));

    public List<LanguageRequirement> detect(String adText) {
        String haystack = normalise(adText);
        if (haystack.isEmpty()) {
            return List.of();
        }

        List<LanguageRequirement> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Phrase pattern : PATTERNS) {
            if (seen.contains(pattern.language())) {
                continue;
            }
            if (haystack.contains(pattern.text())) {
                found.add(new LanguageRequirement(
                        pattern.language(), pattern.level(), pattern.text()));
                seen.add(pattern.language());
            }
        }
        return found;
    }

    /**
     * Lowercase and collapse whitespace — and nothing else.
     *
     * <p>Diacritics are deliberately kept, unlike {@code SelectionGuard}'s fold. That guard
     * compares model output against an ad, where either side may have dropped an accent.
     * Here both sides are known: the patterns above are written with correct Swedish
     * spelling, and stripping å/ä/ö would only widen the match for no benefit.
     */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("\\s+", " ").strip();
    }
}
