package se.caiowain.jobseeker.fit.api;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.fit.api.dto.CandidateLanguageDto;
import se.caiowain.jobseeker.fit.api.dto.PreferencesDto;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

    private final JobPreferencesRepository preferences;

    public PreferencesController(JobPreferencesRepository preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PreferencesDto get() {
        return toDto(preferences.findSingleton());
    }

    @PutMapping
    @Transactional
    public PreferencesDto save(@RequestBody PreferencesDto request) {
        JobPreferences prefs = preferences.findSingleton();
        prefs.setHomeMunicipality(request.homeMunicipality());
        prefs.setAcceptableMunicipalities(request.acceptableMunicipalities());
        if (request.remotePolicy() != null) {
            prefs.setRemotePolicy(request.remotePolicy());
        }
        prefs.setDealBreakers(request.dealBreakers());
        prefs.setUpdatedAt(Instant.now());

        // Replace wholesale. orphanRemoval on the association deletes the old rows, so a
        // second save cannot accumulate duplicate languages.
        prefs.getLanguages().clear();
        List<CandidateLanguageDto> languages =
                request.languages() == null ? List.of() : request.languages();
        int ordinal = 0;
        for (CandidateLanguageDto dto : languages) {
            CandidateLanguage language = new CandidateLanguage();
            language.setLanguage(dto.language());
            language.setLevel(dto.level());
            language.setOrdinal(ordinal++);
            prefs.addLanguage(language);
        }

        return toDto(preferences.save(prefs));
    }

    private static PreferencesDto toDto(JobPreferences prefs) {
        return new PreferencesDto(
                prefs.getHomeMunicipality(),
                prefs.getAcceptableMunicipalities(),
                prefs.getRemotePolicy(),
                prefs.getDealBreakers(),
                prefs.getLanguages().stream()
                        .map(l -> new CandidateLanguageDto(l.getLanguage(), l.getLevel()))
                        .toList());
    }
}
