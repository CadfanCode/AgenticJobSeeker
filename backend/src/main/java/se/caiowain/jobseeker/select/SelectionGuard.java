package se.caiowain.jobseeker.select;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The three guards that make fabrication impossible.
 *
 * <p>Guard A — every bullet number must fall inside the 1..N range the model was shown.
 * Guard B — every requirement phrase must occur in the job ad, so it is a quotation rather
 * than a claim. Guard C — a requirement citing more bullets than the configured maximum is
 * flagged over-broad and rendered as a caution rather than as evidence.
 *
 * <p>A or B failing rejects the whole result. C never rejects: over-broad matching is
 * sloppy, not dishonest, and the reviewer is better served by seeing it flagged.
 */
public class SelectionGuard {

    public GuardVerdict check(SelectionResult result, String jobDescription,
                              int bulletCount, int maxBulletsPerRequirement) {
        List<String> violations = new ArrayList<>();
        Set<Integer> overBroad = new HashSet<>();

        if (result == null || result.requirements() == null || result.requirements().isEmpty()) {
            violations.add("The model returned no requirements");
            return new GuardVerdict(violations, overBroad);
        }

        String haystack = fold(jobDescription);

        for (int i = 0; i < result.requirements().size(); i++) {
            var requirement = result.requirements().get(i);

            String text = requirement.text();
            if (text == null || text.isBlank()) {
                violations.add("Requirement " + i + " has no text");
            } else if (!haystack.contains(fold(text))) {
                violations.add("Requirement " + i + " not found in the job ad: \"" + text.strip() + "\"");
            }

            List<Integer> ids = requirement.bulletIds() == null ? List.of() : requirement.bulletIds();
            for (Integer id : ids) {
                if (id == null || id < 1 || id > bulletCount) {
                    violations.add("Requirement " + i + " cites bullet " + id
                            + ", which is outside 1.." + bulletCount);
                }
            }
            if (ids.size() > maxBulletsPerRequirement) {
                overBroad.add(i);
            }
        }

        List<Integer> ranked = result.rankedBulletIds() == null ? List.of() : result.rankedBulletIds();
        for (Integer id : ranked) {
            if (id == null || id < 1 || id > bulletCount) {
                violations.add("Ranked bullet " + id + " is outside 1.." + bulletCount);
            }
        }

        return new GuardVerdict(violations, overBroad);
    }

    /** Lowercase, strip diacritics and punctuation, collapse whitespace. */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalised = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalised.toLowerCase()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    public record GuardVerdict(List<String> violations, Set<Integer> overBroadRequirements) {

        public boolean accepted() {
            return violations.isEmpty();
        }

        public boolean isOverBroad(int requirementIndex) {
            return overBroadRequirements.contains(requirementIndex);
        }
    }
}
