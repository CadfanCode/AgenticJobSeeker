package se.caiowain.jobseeker.tailor.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.PageDto;
import se.caiowain.jobseeker.tailor.TailoringService;
import se.caiowain.jobseeker.tailor.api.dto.*;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

@RestController
public class TailoringController {

    private final TailoringService service;
    private final TailoredApplicationRepository applications;

    public TailoringController(TailoringService service, TailoredApplicationRepository applications) {
        this.service = service;
        this.applications = applications;
    }

    @PostMapping("/api/jobs/{jobId}/tailor")
    public ResponseEntity<ApplicationDto> tailor(@PathVariable Long jobId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(service.tailor(jobId)));
    }

    @GetMapping("/api/jobs/{jobId}/application")
    public ResponseEntity<ApplicationDto> forJob(@PathVariable Long jobId) {
        return applications.findFirstByJobPostingIdOrderByIdDesc(jobId)
                .map(a -> ResponseEntity.ok(toDto(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/applications")
    public PageDto<ApplicationSummaryDto> queue(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return PageDto.of(
                applications.findAllByOrderByGeneratedAtDesc(PageRequest.of(page, Math.min(size, 100))),
                TailoringController::toSummary);
    }

    @GetMapping("/api/applications/{id}")
    public ResponseEntity<ApplicationDto> detail(@PathVariable Long id) {
        return applications.findById(id)
                .map(a -> ResponseEntity.ok(toDto(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/applications/{id}/letter")
    public ApplicationDto saveLetter(@PathVariable Long id, @RequestBody LetterRequest request) {
        return toDto(service.saveLetter(id, request.prose()));
    }

    @DeleteMapping("/api/applications/{id}")
    public ApplicationDto discard(@PathVariable Long id) {
        return toDto(service.discard(id));
    }

    private static ApplicationSummaryDto toSummary(TailoredApplication a) {
        return new ApplicationSummaryDto(a.getId(), a.getJobPosting().getId(),
                a.getJobPosting().getTitle(), a.getJobPosting().getEmployerName(),
                a.getStatus().name(), a.getCoveragePercent(), a.getGeneratedAt());
    }

    private static ApplicationDto toDto(TailoredApplication a) {
        var requirements = a.getRequirements().stream()
                .map(r -> new RequirementDto(r.getId(), r.getText(), r.getOrdinal(), r.isOverBroad(),
                        r.getEvidence().stream()
                                .map(e -> new EvidenceDto(e.getId(), e.getCvExperienceBulletId(),
                                        e.getBulletText(), e.getOrdinal()))
                                .toList()))
                .toList();

        return new ApplicationDto(a.getId(), a.getJobPosting().getId(),
                a.getJobPosting().getTitle(), a.getJobPosting().getEmployerName(),
                a.getJobPosting().getApplyUrl(), a.getStatus().name(), a.getModelUsed(),
                a.getCoveragePercent(), a.getGeneratedAt(), a.getReviewedAt(),
                a.getLetterProse(), requirements);
    }
}
