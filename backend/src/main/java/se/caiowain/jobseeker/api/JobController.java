package se.caiowain.jobseeker.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.JobDetailDto;
import se.caiowain.jobseeker.api.dto.JobSourceDto;
import se.caiowain.jobseeker.api.dto.JobSummaryDto;
import se.caiowain.jobseeker.api.dto.PageDto;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobQueryService queries;
    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPrescreenRepository prescreens;
    private final JobTriageRepository triages;

    public JobController(JobQueryService queries, JobPostingRepository postings,
                         JobPostingSourceRepository sources,
                         JobPrescreenRepository prescreens, JobTriageRepository triages) {
        this.queries = queries;
        this.postings = postings;
        this.sources = sources;
        this.prescreens = prescreens;
        this.triages = triages;
    }

    @GetMapping
    public PageDto<JobSummaryDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) AtsVendor vendor,
            @RequestParam(required = false) SourceId source,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) TriageState triage,
            @RequestParam(defaultValue = "false") boolean includeGateFailures,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var results = queries.search(q, municipality, vendor, source, sort, triage,
                includeGateFailures, page, Math.min(size, 100));

        // Overhead, not an optimisation. A mappedBy @OneToOne cannot be a true lazy proxy
        // without bytecode enhancement, so Hibernate already issues one select per posting
        // per association (prescreen, triage) while materialising the page above — before
        // this line runs. These two batch lookups add two more queries on top of that; they
        // do not replace the per-row cost. Left as a known inefficiency for a follow-up
        // task rather than restructured here.
        List<Long> ids = results.getContent().stream().map(JobPosting::getId).toList();
        Map<Long, JobPrescreen> fits = ids.isEmpty() ? Map.of()
                : prescreens.findByJobPostingIdIn(ids).stream()
                        .collect(Collectors.toMap(f -> f.getJobPosting().getId(),
                                Function.identity()));
        Map<Long, JobTriage> decisions = ids.isEmpty() ? Map.of()
                : triages.findByJobPostingIdIn(ids).stream()
                        .collect(Collectors.toMap(t -> t.getJobPosting().getId(),
                                Function.identity()));

        return PageDto.of(results,
                job -> toSummary(job, fits.get(job.getId()), decisions.get(job.getId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDetailDto> detail(@PathVariable Long id) {
        return postings.findById(id)
                .map(job -> ResponseEntity.ok(toDetail(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static JobSummaryDto toSummary(JobPosting job, JobPrescreen fit, JobTriage decision) {
        return new JobSummaryDto(job.getId(), job.getTitle(), job.getEmployerName(),
                job.getMunicipality(), job.getAtsVendor(), job.getApplyUrl(),
                job.getPublishedAt(), job.getLastSeenAt(),
                fit == null ? null : fit.getMatchedSkillCount(),
                fit == null ? null : fit.getMatchedSkills(),
                fit == null ? null : fit.getLanguageGate(),
                fit == null ? null : fit.getLanguageNote(),
                fit == null ? null : fit.getLocationGate(),
                fit == null ? null : fit.isDeadlinePassed(),
                decision == null ? null : decision.getState());
    }

    private JobDetailDto toDetail(JobPosting job) {
        var sourceRows = sources.findByJobPostingId(job.getId()).stream()
                .map(s -> new JobSourceDto(s.getSource(), s.getSourceAdId(),
                        s.getSourceUrl(), s.getFetchedAt()))
                .toList();

        return new JobDetailDto(job.getId(), job.getTitle(), job.getEmployerName(),
                job.getEmployerOrgNumber(), job.getMunicipality(), job.getDescription(),
                job.getLanguage(), job.getAtsVendor(), job.getApplyUrl(), job.getCanonicalUrl(),
                job.getPublishedAt(), job.getDeadlineAt(), job.getFirstSeenAt(),
                job.getLastSeenAt(), sourceRows);
    }
}
