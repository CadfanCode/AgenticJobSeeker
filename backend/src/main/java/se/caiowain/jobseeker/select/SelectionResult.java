package se.caiowain.jobseeker.select;

import java.util.List;

/**
 * The model's entire output surface.
 *
 * <p>It can say exactly two things: which numbered bullets are relevant, and which phrases
 * from the ad they answer. It cannot express a claim about the candidate, because the only
 * thing it can say about the candidate is an integer pointing at a sentence they wrote.
 */
public record SelectionResult(
        List<RequirementSelection> requirements,
        List<Integer> rankedBulletIds
) {

    public record RequirementSelection(String text, List<Integer> bulletIds) {
    }
}
