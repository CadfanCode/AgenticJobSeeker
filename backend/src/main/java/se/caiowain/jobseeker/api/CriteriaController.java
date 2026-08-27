package se.caiowain.jobseeker.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.CriteriaDto;
import se.caiowain.jobseeker.domain.SearchCriteria;
import se.caiowain.jobseeker.repo.SearchCriteriaRepository;

import java.util.List;

@RestController
@RequestMapping("/api/criteria")
public class CriteriaController {

    private final SearchCriteriaRepository repository;

    public CriteriaController(SearchCriteriaRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CriteriaDto> list() {
        return repository.findAll().stream().map(CriteriaController::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<CriteriaDto> create(@RequestBody CriteriaDto request) {
        SearchCriteria entity = new SearchCriteria();
        entity.setName(request.name());
        entity.setQuery(request.query());
        entity.setMunicipalityCodes(request.municipalityCodes());
        entity.setMunicipalityNames(request.municipalityNames());
        entity.setOccupationFieldCodes(request.occupationFieldCodes());
        entity.setEnabled(request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(repository.save(entity)));
    }

    static CriteriaDto toDto(SearchCriteria c) {
        return new CriteriaDto(c.getId(), c.getName(), c.getQuery(), c.getMunicipalityCodes(),
                c.getMunicipalityNames(), c.getOccupationFieldCodes(), c.isEnabled());
    }
}
