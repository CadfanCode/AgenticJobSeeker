package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GateEvaluatorTest {

    private final GateEvaluator evaluator = new GateEvaluator(new LanguageRequirementDetector());
    private final Instant now = Instant.parse("2026-08-30T12:00:00Z");

    private JobPosting job(String description, String municipality) {
        JobPosting job = new JobPosting();
        job.setTitle("Backend Developer");
        job.setDescription(description);
        job.setMunicipality(municipality);
        return job;
    }

    private JobPreferences prefsSpeaking(LanguageLevel swedish) {
        JobPreferences prefs = new JobPreferences();
        prefs.setAcceptableMunicipalities("Stockholm, Solna");
        CandidateLanguage sv = new CandidateLanguage();
        sv.setLanguage("sv");
        sv.setLevel(swedish);
        prefs.addLanguage(sv);
        return prefs;
    }

    @Test
    void aLanguageYouDoNotSpeakAtAllIsAHardVeto() {
        JobPreferences prefs = new JobPreferences();
        prefs.setAcceptableMunicipalities("Stockholm");

        GateOutcome outcome = evaluator.evaluate(
                job("Vi kräver flytande svenska.", "Stockholm"), prefs, now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.FAIL);
        assertThat(outcome.languageNote()).contains("flytande svenska");
    }

    @Test
    void aBarAboveYourLevelFlagsRatherThanFailsBecauseFluentVariesByEmployer() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi kräver flytande svenska.", "Stockholm"),
                prefsSpeaking(LanguageLevel.CONVERSATIONAL), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.FLAG);
        assertThat(outcome.languageNote()).contains("flytande svenska");
    }

    @Test
    void aBarAtOrBelowYourLevelPasses() {
        GateOutcome outcome = evaluator.evaluate(
                job("Du behöver svenska i tal och skrift.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void anAdStatingNoLanguageRequirementIsUnknownNotPass() {
        GateOutcome outcome = evaluator.evaluate(
                job("We are hiring a backend engineer.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.UNKNOWN);
        assertThat(outcome.languageNote()).isNull();
    }

    @Test
    void theWorstVerdictAcrossLanguagesWinsAndItsPhraseIsTheOneQuoted() {
        // Swedish passes, English is not spoken at all. The veto must survive the pass.
        GateOutcome outcome = evaluator.evaluate(
                job("Svenska i tal och skrift samt flytande engelska.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.FAIL);
        assertThat(outcome.languageNote()).contains("flytande engelska");
    }

    @Test
    void aMunicipalityOutsideYourListFails() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi söker en utvecklare.", "Malmö"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.FAIL);
    }

    @Test
    void municipalityMatchingIgnoresCase() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi söker en utvecklare.", "STOCKHOLM"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void remoteOnlyMakesLocationIrrelevant() {
        JobPreferences prefs = prefsSpeaking(LanguageLevel.NATIVE);
        prefs.setRemotePolicy(RemotePolicy.REMOTE_ONLY);

        GateOutcome outcome = evaluator.evaluate(job("Vi söker.", "Kiruna"), prefs, now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void anEmptyMunicipalityListMeansNothingWasConfiguredToCheckAgainst() {
        JobPreferences prefs = prefsSpeaking(LanguageLevel.NATIVE);
        prefs.setAcceptableMunicipalities("");

        GateOutcome outcome = evaluator.evaluate(job("Vi söker.", "Kiruna"), prefs, now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void anAdWithNoMunicipalityIsUnknownNotPass() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi söker.", null), prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.UNKNOWN);
    }

    @Test
    void aPastDeadlineIsFlaggedAndAFutureOneIsNot() {
        JobPosting expired = job("Vi söker.", "Stockholm");
        expired.setDeadlineAt(now.minus(1, ChronoUnit.DAYS));
        JobPosting open = job("Vi söker.", "Stockholm");
        open.setDeadlineAt(now.plus(1, ChronoUnit.DAYS));

        JobPreferences prefs = prefsSpeaking(LanguageLevel.NATIVE);

        assertThat(evaluator.evaluate(expired, prefs, now).deadlinePassed()).isTrue();
        assertThat(evaluator.evaluate(open, prefs, now).deadlinePassed()).isFalse();
    }

    @Test
    void anAdWithNoDeadlineHasNotExpired() {
        assertThat(evaluator.evaluate(job("Vi söker.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now).deadlinePassed()).isFalse();
    }
}
