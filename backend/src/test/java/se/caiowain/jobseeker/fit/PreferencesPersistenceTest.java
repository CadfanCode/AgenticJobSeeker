package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PreferencesPersistenceTest extends AbstractIntegrationTest {

    @Autowired
    JobPreferencesRepository preferences;

    @Test
    void theMigrationSeedsASingletonSoTheFormAlwaysHasSomethingToEdit() {
        JobPreferences prefs = preferences.findSingleton();

        assertThat(prefs.getId()).isEqualTo(1L);
        assertThat(prefs.getRemotePolicy()).isEqualTo(RemotePolicy.HYBRID_OK);
    }

    @Test
    void languagesRoundTripWithTheirLevels() {
        JobPreferences prefs = preferences.findSingleton();
        CandidateLanguage swedish = new CandidateLanguage();
        swedish.setLanguage("sv");
        swedish.setLevel(LanguageLevel.CONVERSATIONAL);
        swedish.setOrdinal(0);
        prefs.addLanguage(swedish);

        preferences.saveAndFlush(prefs);

        JobPreferences reloaded = preferences.findSingleton();
        assertThat(reloaded.getLanguages()).hasSize(1);
        assertThat(reloaded.getLanguages().getFirst().getLevel())
                .isEqualTo(LanguageLevel.CONVERSATIONAL);
    }

    @Test
    void municipalitiesAreStoredCommaSeparatedAndReadBackAsAList() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.setAcceptableMunicipalities("Stockholm, Solna ,Sundbyberg");

        preferences.saveAndFlush(prefs);
        JobPreferences reloaded = preferences.findSingleton();

        assertThat(reloaded.acceptableMunicipalityList())
                .containsExactly("Stockholm", "Solna", "Sundbyberg");
    }

    @Test
    void anUnsetMunicipalityListIsEmptyRatherThanASingleBlankEntry() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.setAcceptableMunicipalities("");

        assertThat(prefs.acceptableMunicipalityList()).isEmpty();
    }

    @Test
    void levelsCompareInDeclaredOrder() {
        assertThat(LanguageLevel.FLUENT.atLeast(LanguageLevel.CONVERSATIONAL)).isTrue();
        assertThat(LanguageLevel.CONVERSATIONAL.atLeast(LanguageLevel.FLUENT)).isFalse();
        assertThat(LanguageLevel.NATIVE.atLeast(LanguageLevel.NATIVE)).isTrue();
    }
}
