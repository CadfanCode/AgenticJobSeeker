package se.caiowain.jobseeker.tailor.select;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionGuardTest {

    private final SelectionGuard guard = new SelectionGuard();

    private static final String AD = """
            Har du en bakgrund inom webbutveckling och brinner för mötet mellan teknik och människor?
            Vi söker en Partner Engineer. Har erfarenhet av webbutveckling.
            Har god förståelse API-design. Har mycket god engelska i tal och skrift.
            """;

    @Test
    void acceptsTheRealSpikeResponse() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1)),
                new RequirementSelection("Har god förståelse API-design", List.of(1, 3))),
                List.of(1, 3, 2));

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.violations()).isEmpty();
    }

    @Test
    void rejectsABulletNumberThatDoesNotExist() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(99))),
                List.of(99));

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.violations().toString()).contains("99");
    }

    @Test
    void rejectsARequirementPhraseThatIsNotInTheAd() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av 2nd line support till externa partners", List.of(1))),
                List.of(1));

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.violations().toString()).containsIgnoringCase("not found in the job ad");
    }

    @Test
    void acceptsPhrasesDifferingOnlyByCaseWhitespaceOrPunctuation() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("har   GOD förståelse, API-design!", List.of(1))),
                List.of(1));

        assertThat(guard.check(result, AD, 3, 3).accepted()).isTrue();
    }

    @Test
    void flagsAnOverBroadRequirementWithoutRejectingIt() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 2, 3, 4))),
                List.of(1, 2, 3, 4));

        var verdict = guard.check(result, AD, 4, 3);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.isOverBroad(0)).isTrue();
    }

    @Test
    void anEmptyBulletListIsValid() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har mycket god engelska i tal och skrift", List.of())),
                List.of());

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.isOverBroad(0)).isFalse();
    }

    @Test
    void rejectsRankedIdsOutsideTheBulletRange() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1))),
                List.of(1, 7));

        assertThat(guard.check(result, AD, 3, 3).accepted()).isFalse();
    }

    @Test
    void rejectsANullOrEmptyResult() {
        assertThat(guard.check(new SelectionResult(null, null), AD, 3, 3).accepted()).isFalse();
        assertThat(guard.check(new SelectionResult(List.of(), List.of()), AD, 3, 3).accepted()).isFalse();
    }

    @Test
    void rejectsABlankRequirementPhrase() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("   ", List.of(1))), List.of(1));

        assertThat(guard.check(result, AD, 3, 3).accepted()).isFalse();
    }
}
