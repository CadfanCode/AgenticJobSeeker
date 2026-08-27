package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "ingest_run")
public class IngestRun {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SourceId source;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false) private int fetched;
    @Column(nullable = false) private int created;
    @Column(nullable = false) private int merged;
    @Column(nullable = false) private int errors;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status = RunStatus.RUNNING;

    @Column(columnDefinition = "text")
    private String message;

    public Long getId() { return id; }
    public SourceId getSource() { return source; }
    public void setSource(SourceId source) { this.source = source; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public int getFetched() { return fetched; }
    public void setFetched(int fetched) { this.fetched = fetched; }
    public int getCreated() { return created; }
    public void setCreated(int created) { this.created = created; }
    public int getMerged() { return merged; }
    public void setMerged(int merged) { this.merged = merged; }
    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
