package se.caiowain.jobseeker.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.caiowain.jobseeker.api.dto.StatsDto;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.AtsTenantRepository;
import se.caiowain.jobseeker.repo.IngestRunRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final AtsTenantRepository tenants;
    private final IngestRunRepository runs;

    public StatsController(JobPostingRepository postings, JobPostingSourceRepository sources,
                           AtsTenantRepository tenants, IngestRunRepository runs) {
        this.postings = postings;
        this.sources = sources;
        this.tenants = tenants;
        this.runs = runs;
    }

    @GetMapping
    public StatsDto stats() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (SourceId source : SourceId.values()) {
            bySource.put(source.name(), sources.countBySource(source));
        }

        Map<String, Long> byVendor = new LinkedHashMap<>();
        for (JobPosting job : postings.findAll()) {
            byVendor.merge(job.getAtsVendor().name(), 1L, Long::sum);
        }

        long activeTenants = tenants.findAll().stream().filter(t -> t.isActive()).count();

        var lastRun = runs.findAllByOrderByStartedAtDesc(PageRequest.of(0, 1))
                .stream().findFirst().map(IngestController::toDto).orElse(null);

        return new StatsDto(postings.count(), bySource, byVendor, activeTenants, lastRun);
    }
}
