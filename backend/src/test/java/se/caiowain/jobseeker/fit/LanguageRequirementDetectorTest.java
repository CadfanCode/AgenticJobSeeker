package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRequirementDetectorTest {

    private final LanguageRequirementDetector detector = new LanguageRequirementDetector();

    @Test
    void readsTheCommonSwedishFluencyBar() {
        var found = detector.detect("Vi söker en utvecklare med flytande svenska i tal och skrift.");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().language()).isEqualTo("sv");
        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.FLUENT);
    }

    @Test
    void reportsThePhraseItMatchedSoTheVetoCanBeReviewed() {
        var found = detector.detect("Du behöver svenska i tal och skrift för rollen.");

        assertThat(found.getFirst().phrase()).isEqualTo("svenska i tal och skrift");
        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.PROFESSIONAL);
    }

    @Test
    void readsBothLanguagesWhenAnAdRequiresBoth() {
        var found = detector.detect(
                "Vi kräver flytande svenska och goda kunskaper i engelska.");

        assertThat(found).hasSize(2);
        assertThat(found).extracting(LanguageRequirement::language)
                .containsExactlyInAnyOrder("sv", "en");
    }

    @Test
    void theMoreSpecificPhraseWinsOverTheOneItContains() {
        // "goda kunskaper i svenska" is a substring of "mycket goda kunskaper i svenska",
        // so pattern order decides the answer. The stronger bar must win.
        var found = detector.detect("Vi förutsätter mycket goda kunskaper i svenska.");

        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.FLUENT);
    }

    @Test
    void readsEnglishLanguageAds() {
        var found = detector.detect("We require fluent Swedish and professional English.");

        assertThat(found).extracting(LanguageRequirement::language)
                .containsExactlyInAnyOrder("sv", "en");
    }

    @Test
    void isNotFooledByCasingOrRaggedWhitespace() {
        var found = detector.detect("FLYTANDE   SVENSKA\n krävs.");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.FLUENT);
    }

    @Test
    void anAdStatingNoLanguageRequirementYieldsNothing() {
        // Nothing, not a pass. Turning this into PASS is what the UNKNOWN verdict exists
        // to prevent — see GateEvaluator.
        assertThat(detector.detect("We are hiring a backend engineer for our platform team."))
                .isEmpty();
    }

    @Test
    void nullAndBlankAdsAreSafe() {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("   ")).isEmpty();
    }
}
