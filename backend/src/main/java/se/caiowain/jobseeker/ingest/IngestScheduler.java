package se.caiowain.jobseeker.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobseeker.ingest.enabled", havingValue = "true", matchIfMissing = true)
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestOrchestrator orchestrator;

    public IngestScheduler(IngestOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${jobseeker.ingest.schedule-cron}")
    public void scheduledIngest() {
        log.info("Scheduled ingest starting");
        orchestrator.runAll().forEach(run ->
                log.info("  {} -> {} (fetched={}, created={}, merged={}, errors={})",
                        run.getSource(), run.getStatus(), run.getFetched(),
                        run.getCreated(), run.getMerged(), run.getErrors()));
    }
}
