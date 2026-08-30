package se.caiowain.jobseeker.fit.api;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.fit.DeepFitService;
import se.caiowain.jobseeker.fit.PrescreenService;
import se.caiowain.jobseeker.fit.PrescreenSummary;
import se.caiowain.jobseeker.fit.TriageService;
import se.caiowain.jobseeker.fit.api.dto.DeepFitDto;
import se.caiowain.jobseeker.fit.api.dto.PrescreenSummaryDto;
import se.caiowain.jobseeker.fit.api.dto.TriageRequest;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;
import se.caiowain.jobseeker.fit.domain.JobDeepFitGap;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;

@RestController
public class FitController {

    private final PrescreenService prescreen;
    private final DeepFitService deepFit;
    private final TriageService triage;
    private final JobDeepFitRepository deepFits;

    public FitController(PrescreenService prescreen, DeepFitService deepFit,
                         TriageService triage, JobDeepFitRepository deepFits) {
        this.prescreen = prescreen;
        this.deepFit = deepFit;
        this.triage = triage;
        this.deepFits = deepFits;
    }

    /** Cheap and corpus-wide. Works with Ollama stopped. */
    @PostMapping("/api/fit/prescreen")
    public PrescreenSummaryDto prescreen() {
        PrescreenSummary summary = prescreen.run();
        return new PrescreenSummaryDto(
                summary.postings(), summary.withMatches(), summary.computedAt());
    }

    /** Roughly 94 seconds on this hardware. The frontend shows a spinner saying so. */
    @PostMapping("/api/jobs/{id}/fit")
    public DeepFitDto score(@PathVariable Long id) {
        return toDto(deepFit.score(id));
    }

    @GetMapping("/api/jobs/{id}/fit")
    @Transactional(readOnly = true)
    public ResponseEntity<DeepFitDto> latest(@PathVariable Long id) {
        return deepFits.findFirstByJobPostingIdOrderByDeepScoredAtDesc(id)
                .map(fit -> ResponseEntity.ok(toDto(fit)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/jobs/{id}/triage")
    public TriageRequest decide(@PathVariable Long id, @RequestBody TriageRequest request) {
        JobTriage decision = triage.decide(id, request.state(), request.note());
        return new TriageRequest(decision.getState(), decision.getNote());
    }

    private static DeepFitDto toDto(JobDeepFit fit) {
        return new DeepFitDto(
                fit.getJobPosting().getId(),
                fit.getCoveragePercent(),
                fit.getRequirementCount(),
                fit.getGaps().stream().map(JobDeepFitGap::getText).toList(),
                fit.getModelUsed(),
                fit.getDeepScoredAt());
    }
}
