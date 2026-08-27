package se.caiowain.jobseeker.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.IngestRunDto;
import se.caiowain.jobseeker.api.dto.PageDto;
import se.caiowain.jobseeker.domain.IngestRun;
import se.caiowain.jobseeker.ingest.IngestOrchestrator;
import se.caiowain.jobseeker.repo.IngestRunRepository;

import java.util.List;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestOrchestrator orchestrator;
    private final IngestRunRepository runs;

    public IngestController(IngestOrchestrator orchestrator, IngestRunRepository runs) {
        this.orchestrator = orchestrator;
        this.runs = runs;
    }

    @PostMapping("/run")
    public List<IngestRunDto> run() {
        return orchestrator.runAll().stream().map(IngestController::toDto).toList();
    }

    @GetMapping("/runs")
    public PageDto<IngestRunDto> history(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageDto.of(runs.findAllByOrderByStartedAtDesc(PageRequest.of(page, Math.min(size, 100))),
                IngestController::toDto);
    }

    static IngestRunDto toDto(IngestRun run) {
        return new IngestRunDto(run.getId(), run.getSource(), run.getStartedAt(), run.getFinishedAt(),
                run.getFetched(), run.getCreated(), run.getMerged(), run.getErrors(),
                run.getStatus(), run.getMessage());
    }
}
