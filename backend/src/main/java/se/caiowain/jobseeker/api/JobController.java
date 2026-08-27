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
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobQueryService queries;
    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;

    public JobController(JobQueryService queries, JobPostingRepository postings,
                         JobPostingSourceRepository sources) {
        this.queries = queries;
        this.postings = postings;
        this.sources = sources;
    }

    @GetMapping
    public PageDto<JobSummaryDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) AtsVendor vendor,
            @RequestParam(required = false) SourceId source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return PageDto.of(queries.search(q, municipality, vendor, source, page, Math.min(size, 100)),
                JobController::toSummary);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDetailDto> detail(@PathVariable Long id) {
        return postings.findById(id)
                .map(job -> ResponseEntity.ok(toDetail(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static JobSummaryDto toSummary(JobPosting job) {
        return new JobSummaryDto(job.getId(), job.getTitle(), job.getEmployerName(),
                job.getMunicipality(), job.getAtsVendor(), job.getApplyUrl(),
                job.getPublishedAt(), job.getLastSeenAt());
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
