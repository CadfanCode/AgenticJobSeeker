package se.caiowain.jobseeker.fit.api;

import jakarta.validation.Valid;
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
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

@RestController
public class FitController {

    private final PrescreenService prescreen;
    private final DeepFitService deepFit;
    private final TriageService triage;
    private final JobDeepFitRepository deepFits;
    private final CvProfileRepository profiles;

    public FitController(PrescreenService prescreen, DeepFitService deepFit,
                         TriageService triage, JobDeepFitRepository deepFits,
                         CvProfileRepository profiles) {
        this.prescreen = prescreen;
        this.deepFit = deepFit;
        this.triage = triage;
        this.deepFits = deepFits;
        this.profiles = profiles;
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

    /**
     * Scoped to the current {@code READY} profile, the same one {@link DeepFitService#score}
     * would use — a score from a superseded profile must never be served as though it
     * describes the CV on file today. No ready profile, or no row for it, is an ordinary
     * "not scored yet" state: 404, same as the frontend already expects.
     */
    @GetMapping("/api/jobs/{id}/fit")
    @Transactional(readOnly = true)
    public ResponseEntity<DeepFitDto> latest(@PathVariable Long id) {
        return profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .flatMap(profile -> deepFits.findByJobPostingIdAndCvProfileId(id, profile.getId()))
                .map(fit -> ResponseEntity.ok(toDto(fit)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/jobs/{id}/triage")
    public TriageRequest decide(@PathVariable Long id, @Valid @RequestBody TriageRequest request) {
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
