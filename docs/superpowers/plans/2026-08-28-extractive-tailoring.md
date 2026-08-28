# Extractive Tailoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For a job you choose, produce an application package — requirement→evidence mapping, tailored CV, letter skeleton — assembled entirely from sentences you wrote.

**Architecture:** A local Ollama model receives the job description and a numbered list of your CV bullets, and returns **only** bullet indexes plus requirement phrases copied verbatim from the ad. Three deterministic guards validate that output; anything the employer would read is then assembled in Java from your own profile rows. Fabrication is structurally impossible because the model cannot emit a sentence about you — only an integer pointing at one you already wrote.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-model-ollama`), Ollama `qwen2.5:7b-instruct` running locally, PostgreSQL, Flyway, Testcontainers, React 19 + Vite 8 + Tailwind 4.

**Spec:** `docs/superpowers/specs/2026-08-28-extractive-tailoring-design.md`

## Global Constraints

Everything from Slices 1 and 2a still applies. Repeated because they are easy to get wrong:

- **Spring Boot version is exactly `4.1.1`.** Never `4.1.1.RELEASE`.
- **Jackson 3** — import `tools.jackson.databind.*`, never `com.fasterxml.jackson.databind.*`. `JsonNode.asText()` is `asString()`.
- **`@AutoConfigureMockMvc` is `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.**
- **Entity `byte[]` uses `columnDefinition = "bytea"`, never `@Lob`** (which maps to `oid`).
- **Mockito re-stubbing:** use `doReturn(...).when(mock).method(...)`, not `when(mock.method(...))`, when a method is already stubbed to throw — `when()` invokes the mock.
- **Maven is not installed.** Build with `./mvnw` from `backend/` after `export JAVA_HOME=/usr/lib/jvm/default`.
- **PostgreSQL is on port 5433.**
- **No test may call Ollama or any network host.** `ChatClient` is stubbed in every test.
- **Spring AI 2.0.1 Ollama facts, verified against the jars — do not guess these:**
  - Config prefixes: `spring.ai.ollama` (connection), `spring.ai.ollama.chat` (chat), `spring.ai.ollama.init` (initialization).
  - `spring.ai.ollama.init.pull-model-strategy` defaults to `NEVER`, so autoconfiguration performs **no** startup network call. Pin it explicitly anyway.
  - The options class is **`OllamaChatOptions`**, not `OllamaOptions`.
  - `OllamaChatOptions.builder()` returns a builder that extends `DefaultToolCallingChatOptions.Builder`, so it **is** accepted by `ChatClientRequestSpec.options(B extends ChatOptions.Builder<?>)`.
  - The builder's only `model(...)` overload takes the **`OllamaModel` enum**, which does not contain `qwen2.5:7b-instruct`. **Set the model in `application.yml`, never in code.**
  - Builder methods available: `.format(Object)`, `.numCtx(Integer)`, `.numPredict(Integer)`, `.keepAlive(String)`.
- **Model is `qwen2.5:7b-instruct`**, configurable. Generation takes 60–120 seconds on this hardware; do not write tests or timeouts that assume it is fast.
- **The model never emits candidate-facing prose.** Any change that lets model output flow into an employer-visible string is a design violation, not an improvement.
- **A `@Transactional` method that saves a failure record and then throws must declare
  `noRollbackFor` for that exception**, or the record is rolled back and the failure
  becomes invisible. `TailoringService.tailor` depends on this.
- **TDD:** failing test, watch it fail, minimal implementation, watch it pass, commit.
- Every task ends with a commit.

---

## File Structure

**Backend** (`backend/src/main/java/se/caiowain/jobseeker/`):

| Path | Responsibility |
|---|---|
| `tailor/domain/TailoredApplication.java` | Application root: status, coverage, prose |
| `tailor/domain/ApplicationRequirement.java` | One requirement quoted from the ad |
| `tailor/domain/ApplicationEvidence.java` | One bullet supporting a requirement (FK + snapshot) |
| `tailor/domain/ApplicationStatus.java` | Enum: DRAFT, APPROVED, DISCARDED, GENERATION_FAILED |
| `tailor/repo/TailoredApplicationRepository.java` | Spring Data repository |
| `tailor/select/SelectionResult.java` | What the model returns (indexes + quoted phrases) |
| `tailor/select/NumberedBullet.java` | Prompt index ↔ profile bullet id + text |
| `tailor/select/TailoringPromptBuilder.java` | Pure: builds system/user prompts, numbers bullets |
| `tailor/select/SelectionGuard.java` | Pure: guards A, B, C |
| `tailor/select/OllamaSelectionClient.java` | The only component that calls a model |
| `tailor/ApplicationAssembler.java` | Pure: SelectionResult + profile → entities |
| `tailor/TailoringService.java` | Orchestration, retry, persistence, transitions |
| `tailor/api/TailoringController.java` | `/api/jobs/{id}/tailor`, `/api/applications/**` |
| `tailor/api/dto/*.java` | Response DTOs |

**Frontend** (`frontend/src/`): `applicationTypes.ts`, `api/applicationClient.ts`, `pages/ApplicationQueue.tsx`, `pages/ApplicationReview.tsx`, `components/CoverageBar.tsx`.

---

## Task 1: Migrate to Ollama and add the V6 schema

Slice 2a currently depends on Anthropic. This task makes the whole project free to run.

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/se/caiowain/jobseeker/profile/extract/CvProfileExtractor.java`
- Modify: `backend/src/test/java/se/caiowain/jobseeker/profile/extract/CvProfileExtractorTest.java`
- Modify: `backend/src/test/java/se/caiowain/jobseeker/AbstractIntegrationTest.java`
- Create: `backend/src/main/resources/db/migration/V6__tailored_application.sql`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/TailoringSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Slice 2a's `CvProfileExtractor`
- Produces: tables `tailored_application`, `application_requirement`, `application_evidence`; `CvProfileExtractor` backed by Ollama with `isAvailable()` reading `spring.ai.ollama.chat.options.model`

- [ ] **Step 1: Swap the starter in `backend/pom.xml`**

Replace the Anthropic dependency block:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
    <version>2.0.1</version>
</dependency>
```

with:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
    <version>2.0.1</version>
</dependency>
```

- [ ] **Step 2: Replace the Anthropic config in `application.yml`**

Delete the whole `spring.ai.anthropic:` block and add:

```yaml
spring.ai.ollama:
  base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
  init:
    pull-model-strategy: never
  chat:
    options:
      model: ${OLLAMA_MODEL:qwen2.5:7b-instruct}
      temperature: 0.2
      num-ctx: 8192
      num-predict: 600
```

Under the existing `jobseeker:` block, add as a sibling of `profile:`:

```yaml
  tailor:
    max-bullets-per-requirement: 3
    request-timeout-seconds: 300
```

- [ ] **Step 3: Point `CvProfileExtractor` at Ollama**

Replace its Anthropic imports and options with Ollama. The model is **not** set in code —
`OllamaChatOptions.Builder.model(...)` only accepts the `OllamaModel` enum, which does not
contain `qwen2.5:7b-instruct`. Configuration supplies it.

Change the imports from:

```java
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;
import org.springframework.ai.anthropic.AnthropicChatOptions;
```

to:

```java
import org.springframework.ai.ollama.api.OllamaChatOptions;
```

Change the constructor and availability check:

```java
    public CvProfileExtractor(ChatClient.Builder chatClientBuilder,
                              @Value("${spring.ai.ollama.chat.options.model:}") String model,
                              @Value("${spring.ai.ollama.base-url:}") String baseUrl) {
        this.chatClientBuilder = chatClientBuilder;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    /** A local model needs no credential; availability means a model is configured. */
    public boolean isAvailable() {
        return model != null && !model.isBlank();
    }
```

and the call:

```java
            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user("Extract this CV:\n\n" + sourceText)
                    .options(OllamaChatOptions.builder().format("json"))
                    .call()
                    .entity(ExtractedProfile.class);
```

Delete the now-unused `apiKey` and `effort` fields. Update the exception message in
`extract(...)`:

```java
            throw new ExtractionUnavailableException(
                    "CV extraction needs a local model. Start Ollama and set "
                            + "spring.ai.ollama.chat.options.model.");
```

- [ ] **Step 4: Update the extractor's tests for the new constructor**

In `CvProfileExtractorTest`, replace every four-argument construction
`new CvProfileExtractor(builder, "sk-ant-test", "claude-opus-5", "HIGH")` with
`new CvProfileExtractor(builder, "qwen2.5:7b-instruct", "http://localhost:11434")`, and the
unavailable case `new CvProfileExtractor(stubbedBuilder(CANNED), "", "claude-opus-5", "HIGH")`
with `new CvProfileExtractor(stubbedBuilder(CANNED), "", "http://localhost:11434")`.

Update the two assertions that named the old provider:

```java
        assertThatThrownBy(() -> extractor.extract("some cv text"))
                .isInstanceOf(ExtractionUnavailableException.class)
                .hasMessageContaining("Ollama");
```

```java
    @Test
    void reportsTheConfiguredModelName() {
        var extractor = new CvProfileExtractor(
                stubbedBuilder(CANNED), "qwen2.5:7b-instruct", "http://localhost:11434");
        assertThat(extractor.modelName()).isEqualTo("qwen2.5:7b-instruct");
    }
```

- [ ] **Step 5: Update the test property override**

In `AbstractIntegrationTest.testProperties(...)`, replace the Anthropic line

```java
        registry.add("jobseeker.sources.jobtech.base-url", () -> "http://127.0.0.1:1");
```

keeping it, and add beneath it:

```java
        // No test may reach a model host. Point Ollama at a dead port and leave the
        // model name set, so availability logic is exercised without any network call.
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:1");
        registry.add("spring.ai.ollama.chat.options.model", () -> "qwen2.5:7b-instruct");
```

- [ ] **Step 6: Write the failing migration test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/TailoringSchemaMigrationTest.java`:

```java
package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TailoringSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTailoringTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "tailored_application", "application_requirement", "application_evidence");
    }

    @Test
    void oneApplicationPerJobAndProfile() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'tailored_application'
                  and indexdef like '%UNIQUE%job_posting_id%cv_profile_id%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void evidenceKeepsATextSnapshotAndTolerationForDeletedBullets() {
        // The snapshot is what makes an approved application immutable; the nullable FK
        // is what lets a bullet be deleted without gutting past applications.
        String nullable = jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'application_evidence' and column_name = 'cv_experience_bullet_id'
                """, String.class);
        String snapshotNullable = jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'application_evidence' and column_name = 'bullet_text'
                """, String.class);

        assertThat(nullable).isEqualTo("YES");
        assertThat(snapshotNullable).isEqualTo("NO");
    }
}
```

- [ ] **Step 7: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringSchemaMigrationTest
```

Expected: FAIL — the tables do not exist.

- [ ] **Step 8: Write the migration**

`backend/src/main/resources/db/migration/V6__tailored_application.sql`:

```sql
CREATE TABLE tailored_application (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_posting_id    BIGINT      NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    cv_profile_id     BIGINT      NOT NULL REFERENCES cv_profile (id) ON DELETE CASCADE,
    status            VARCHAR(32) NOT NULL,
    model_used        VARCHAR(64),
    coverage_percent  INTEGER     NOT NULL DEFAULT 0,
    generated_at      TIMESTAMPTZ NOT NULL,
    reviewed_at       TIMESTAMPTZ,
    letter_prose      TEXT,
    raw_model_output  TEXT
);

CREATE TABLE application_requirement (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tailored_application_id BIGINT  NOT NULL REFERENCES tailored_application (id) ON DELETE CASCADE,
    text                    TEXT    NOT NULL,
    ordinal                 INTEGER NOT NULL DEFAULT 0,
    over_broad              BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE application_evidence (
    id                        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_requirement_id BIGINT NOT NULL REFERENCES application_requirement (id) ON DELETE CASCADE,
    -- Nullable and SET NULL: deleting a CV bullet must not gut past applications.
    cv_experience_bullet_id   BIGINT REFERENCES cv_experience_bullet (id) ON DELETE SET NULL,
    -- The snapshot is what makes an approved application immutable.
    bullet_text               TEXT    NOT NULL,
    ordinal                   INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_tailored_application_job_profile
    ON tailored_application (job_posting_id, cv_profile_id);
CREATE INDEX ix_tailored_application_job     ON tailored_application (job_posting_id);
CREATE INDEX ix_tailored_application_status  ON tailored_application (status);
CREATE INDEX ix_application_requirement_app  ON application_requirement (tailored_application_id);
CREATE INDEX ix_application_evidence_req     ON application_evidence (application_requirement_id);
```

- [ ] **Step 9: Run the whole suite — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: all tests PASS, including the migrated Slice 2a extractor tests.

- [ ] **Step 10: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat: migrate extraction to local Ollama and add V6 tailoring schema"
```

---

## Task 2: Tailoring domain and repository

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/domain/{ApplicationStatus,TailoredApplication,ApplicationRequirement,ApplicationEvidence}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/repo/TailoredApplicationRepository.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/TailoredApplicationPersistenceTest.java`

**Interfaces:**
- Consumes: V6 schema; `JobPosting` (Slice 1); `CvProfile` (Slice 2a)
- Produces:
  - `ApplicationStatus` enum: `DRAFT`, `APPROVED`, `DISCARDED`, `GENERATION_FAILED`
  - `TailoredApplicationRepository.findByJobPostingIdAndCvProfileId(Long, Long) : Optional<TailoredApplication>`
  - `TailoredApplicationRepository.findAllByOrderByGeneratedAtDesc(Pageable) : Page<TailoredApplication>`
  - `TailoredApplication.addRequirement(ApplicationRequirement)`, `ApplicationRequirement.addEvidence(ApplicationEvidence)`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/TailoredApplicationPersistenceTest.java`:

```java
package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.*;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TailoredApplicationPersistenceTest extends AbstractIntegrationTest {

    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    private JobPosting job;
    private CvProfile profile;

    @BeforeEach
    void seed() {
        applications.deleteAll();

        JobPosting j = new JobPosting();
        j.setFingerprint("fp-tailor-" + System.nanoTime());
        j.setCanonicalUrl("https://example.se/job");
        j.setTitle("Partner Engineer");
        j.setEmployerName("Academic Work");
        j.setDescription("Har erfarenhet av webbutveckling.");
        j.setAtsVendor(AtsVendor.OTHER);
        j.setStatus(JobStatus.DISCOVERED);
        j.setFirstSeenAt(Instant.now());
        j.setLastSeenAt(Instant.now());
        job = jobs.save(j);

        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1L);
        doc.setSha256("sha-tailor-" + System.nanoTime());
        doc.setContent(new byte[]{1});
        doc.setExtractedText("Built REST APIs in Java and Spring Boot.");
        doc.setUploadedAt(Instant.now());
        documents.save(doc);

        CvProfile p = new CvProfile();
        p.setCvDocument(doc);
        p.setFullName("Cai Wain");
        p.setStatus(ProfileStatus.READY);
        profile = profiles.save(p);
    }

    @Test
    void persistsApplicationWithRequirementsAndEvidence() {
        TailoredApplication app = new TailoredApplication();
        app.setJobPosting(job);
        app.setCvProfile(profile);
        app.setStatus(ApplicationStatus.DRAFT);
        app.setModelUsed("qwen2.5:7b-instruct");
        app.setCoveragePercent(50);
        app.setGeneratedAt(Instant.now());

        ApplicationRequirement req = new ApplicationRequirement();
        req.setText("Har erfarenhet av webbutveckling.");
        req.setOrdinal(0);
        req.setOverBroad(false);
        app.addRequirement(req);

        ApplicationEvidence ev = new ApplicationEvidence();
        ev.setBulletText("Built REST APIs in Java and Spring Boot.");
        ev.setOrdinal(0);
        req.addEvidence(ev);

        applications.save(app);

        TailoredApplication loaded =
                applications.findByJobPostingIdAndCvProfileId(job.getId(), profile.getId()).orElseThrow();
        assertThat(loaded.getRequirements()).hasSize(1);
        assertThat(loaded.getRequirements().getFirst().getEvidence()).hasSize(1);
        assertThat(loaded.getRequirements().getFirst().getEvidence().getFirst().getBulletText())
                .isEqualTo("Built REST APIs in Java and Spring Boot.");
        assertThat(loaded.getCoveragePercent()).isEqualTo(50);
    }

    @Test
    void evidenceSurvivesWithoutABulletReference() {
        // A null bullet FK is legitimate: the CV bullet may have been deleted since.
        TailoredApplication app = new TailoredApplication();
        app.setJobPosting(job);
        app.setCvProfile(profile);
        app.setStatus(ApplicationStatus.APPROVED);
        app.setGeneratedAt(Instant.now());

        ApplicationRequirement req = new ApplicationRequirement();
        req.setText("Har erfarenhet av webbutveckling.");
        req.setOrdinal(0);
        app.addRequirement(req);

        ApplicationEvidence ev = new ApplicationEvidence();
        ev.setCvExperienceBulletId(null);
        ev.setBulletText("Snapshot text that outlives its bullet");
        ev.setOrdinal(0);
        req.addEvidence(ev);

        applications.save(app);

        assertThat(applications.findByJobPostingIdAndCvProfileId(job.getId(), profile.getId())
                .orElseThrow().getRequirements().getFirst().getEvidence().getFirst().getBulletText())
                .isEqualTo("Snapshot text that outlives its bullet");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoredApplicationPersistenceTest
```

Expected: compilation failure — the domain classes do not exist.

- [ ] **Step 3: Write `ApplicationStatus`**

```java
package se.caiowain.jobseeker.tailor.domain;

public enum ApplicationStatus { DRAFT, APPROVED, DISCARDED, GENERATION_FAILED }
```

- [ ] **Step 4: Write `TailoredApplication`**

```java
package se.caiowain.jobseeker.tailor.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tailored_application")
public class TailoredApplication {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "coverage_percent", nullable = false) private int coveragePercent;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "letter_prose", columnDefinition = "text") private String letterProse;
    @Column(name = "raw_model_output", columnDefinition = "text") private String rawModelOutput;

    @OneToMany(mappedBy = "tailoredApplication", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<ApplicationRequirement> requirements = new ArrayList<>();

    public void addRequirement(ApplicationRequirement r) {
        r.setTailoredApplication(this);
        requirements.add(r);
    }

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public int getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(int coveragePercent) { this.coveragePercent = coveragePercent; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getLetterProse() { return letterProse; }
    public void setLetterProse(String letterProse) { this.letterProse = letterProse; }
    public String getRawModelOutput() { return rawModelOutput; }
    public void setRawModelOutput(String rawModelOutput) { this.rawModelOutput = rawModelOutput; }
    public List<ApplicationRequirement> getRequirements() { return requirements; }
}
```

- [ ] **Step 5: Write `ApplicationRequirement` and `ApplicationEvidence`**

```java
package se.caiowain.jobseeker.tailor.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "application_requirement")
public class ApplicationRequirement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tailored_application_id", nullable = false)
    private TailoredApplication tailoredApplication;

    /** Quoted verbatim from the job ad. Never a claim about the candidate. */
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false) private int ordinal;
    @Column(name = "over_broad", nullable = false) private boolean overBroad;

    @OneToMany(mappedBy = "applicationRequirement", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<ApplicationEvidence> evidence = new ArrayList<>();

    public void addEvidence(ApplicationEvidence e) {
        e.setApplicationRequirement(this);
        evidence.add(e);
    }

    public Long getId() { return id; }
    public TailoredApplication getTailoredApplication() { return tailoredApplication; }
    public void setTailoredApplication(TailoredApplication a) { this.tailoredApplication = a; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isOverBroad() { return overBroad; }
    public void setOverBroad(boolean overBroad) { this.overBroad = overBroad; }
    public List<ApplicationEvidence> getEvidence() { return evidence; }
}
```

```java
package se.caiowain.jobseeker.tailor.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "application_evidence")
public class ApplicationEvidence {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_requirement_id", nullable = false)
    private ApplicationRequirement applicationRequirement;

    /** Nullable on purpose: the bullet may be deleted after the application is approved. */
    @Column(name = "cv_experience_bullet_id")
    private Long cvExperienceBulletId;

    /** The snapshot. This is what makes an approved application immutable. */
    @Column(name = "bullet_text", nullable = false, columnDefinition = "text")
    private String bulletText;

    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public ApplicationRequirement getApplicationRequirement() { return applicationRequirement; }
    public void setApplicationRequirement(ApplicationRequirement r) { this.applicationRequirement = r; }
    public Long getCvExperienceBulletId() { return cvExperienceBulletId; }
    public void setCvExperienceBulletId(Long id) { this.cvExperienceBulletId = id; }
    public String getBulletText() { return bulletText; }
    public void setBulletText(String bulletText) { this.bulletText = bulletText; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
```

- [ ] **Step 6: Write the repository**

```java
package se.caiowain.jobseeker.tailor.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.util.Optional;

public interface TailoredApplicationRepository extends JpaRepository<TailoredApplication, Long> {
    Optional<TailoredApplication> findByJobPostingIdAndCvProfileId(Long jobPostingId, Long cvProfileId);
    Optional<TailoredApplication> findFirstByJobPostingIdOrderByIdDesc(Long jobPostingId);
    Page<TailoredApplication> findAllByOrderByGeneratedAtDesc(Pageable pageable);
}
```

- [ ] **Step 7: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoredApplicationPersistenceTest
```

Expected: PASS, 2 tests. `ddl-auto: validate` also proves the entities match V6.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat: add tailored application domain and repository"
```

---

## Task 3: Selection result shape and prompt builder

Both pure — no Spring, no I/O.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/select/{SelectionResult,NumberedBullet,TailoringPromptBuilder}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/select/TailoringPromptBuilderTest.java`

**Interfaces:**
- Consumes: `CvProfile` (Slice 2a)
- Produces:
  - `SelectionResult(List<RequirementSelection> requirements, List<Integer> rankedBulletIds)`
  - `SelectionResult.RequirementSelection(String text, List<Integer> bulletIds)`
  - `NumberedBullet(int index, Long bulletId, String text)`
  - `TailoringPromptBuilder.numberBullets(CvProfile) : List<NumberedBullet>`
  - `TailoringPromptBuilder.systemPrompt() : String`
  - `TailoringPromptBuilder.userPrompt(String jobDescription, List<NumberedBullet>) : String`

> **Why the prompt uses 1..N indexes rather than database ids.** Database ids are large and
> sparse, which invites a small model to transpose digits or invent plausible-looking
> numbers. A dense 1..N range makes an out-of-range value obvious, and the mapping back to
> real bullet ids happens in Java where it cannot go wrong.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/select/TailoringPromptBuilderTest.java`:

```java
package se.caiowain.jobseeker.tailor.select;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TailoringPromptBuilderTest {

    private final TailoringPromptBuilder builder = new TailoringPromptBuilder();

    private CvProfile profileWithBullets(String... texts) {
        CvProfile profile = new CvProfile();
        CvExperience experience = new CvExperience();
        experience.setEmployer("Nordic Systems AB");
        experience.setTitle("Senior Software Engineer");
        experience.setOrdinal(0);
        profile.addExperience(experience);
        int i = 0;
        for (String t : texts) {
            CvExperienceBullet bullet = new CvExperienceBullet();
            bullet.setText(t);
            bullet.setOrdinal(i++);
            experience.addBullet(bullet);
        }
        return profile;
    }

    @Test
    void numbersBulletsFromOneUpwards() {
        List<NumberedBullet> numbered = builder.numberBullets(
                profileWithBullets("Built REST APIs", "Reduced deployment time"));

        assertThat(numbered).hasSize(2);
        assertThat(numbered.get(0).index()).isEqualTo(1);
        assertThat(numbered.get(1).index()).isEqualTo(2);
        assertThat(numbered.get(0).text()).isEqualTo("Built REST APIs");
    }

    @Test
    void skipsBlankBullets() {
        assertThat(builder.numberBullets(profileWithBullets("Built REST APIs", "  ", "")))
                .hasSize(1);
    }

    @Test
    void userPromptContainsTheDescriptionAndEveryNumberedBullet() {
        List<NumberedBullet> numbered = builder.numberBullets(
                profileWithBullets("Built REST APIs", "Reduced deployment time"));

        String prompt = builder.userPrompt("MARKER-JOB-TEXT", numbered);

        assertThat(prompt).contains("MARKER-JOB-TEXT");
        assertThat(prompt).contains("1. Built REST APIs");
        assertThat(prompt).contains("2. Reduced deployment time");
    }

    @Test
    void systemPromptForbidsWritingClaims() {
        String system = builder.systemPrompt();
        assertThat(system).containsIgnoringCase("verbatim");
        assertThat(system).containsIgnoringCase("never");
        assertThat(system).containsIgnoringCase("json");
    }

    @Test
    void handlesAProfileWithNoBullets() {
        assertThat(builder.numberBullets(new CvProfile())).isEmpty();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringPromptBuilderTest
```

Expected: compilation failure — the types do not exist.

- [ ] **Step 3: Write `NumberedBullet` and `SelectionResult`**

```java
package se.caiowain.jobseeker.tailor.select;

/**
 * A CV bullet as presented to the model.
 *
 * @param index    the 1..N number shown in the prompt
 * @param bulletId the real profile bullet id, never shown to the model
 */
public record NumberedBullet(int index, Long bulletId, String text) {
}
```

```java
package se.caiowain.jobseeker.tailor.select;

import java.util.List;

/**
 * The model's entire output surface.
 *
 * <p>It can say exactly two things: which numbered bullets are relevant, and which phrases
 * from the ad they answer. It cannot express a claim about the candidate, because the only
 * thing it can say about the candidate is an integer pointing at a sentence they wrote.
 */
public record SelectionResult(
        List<RequirementSelection> requirements,
        List<Integer> rankedBulletIds
) {

    public record RequirementSelection(String text, List<Integer> bulletIds) {
    }
}
```

- [ ] **Step 4: Write `TailoringPromptBuilder`**

```java
package se.caiowain.jobseeker.tailor.select;

import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.util.ArrayList;
import java.util.List;

/** Pure prompt construction. No Spring, no I/O. */
public class TailoringPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You match a candidate's CV bullets to a job ad.

            You NEVER write new claims about the candidate. You only return:
              - bullet numbers, chosen from the numbered list you are given
              - requirement phrases copied VERBATIM from the job ad

            Rules:
            - Copy requirement phrases exactly as written in the ad. Do not translate,
              summarise or rephrase them.
            - Only use bullet numbers that appear in the list. Never invent a number.
            - A requirement should cite at most 3 bullets, and only genuinely relevant ones.
              If nothing in the CV supports a requirement, return an empty bulletIds list —
              that is a useful answer, not a failure.
            - Respond with JSON only. No commentary, no markdown fences.
            """;

    /** Flattens the profile's bullets into a dense 1..N list for the prompt. */
    public List<NumberedBullet> numberBullets(CvProfile profile) {
        List<NumberedBullet> numbered = new ArrayList<>();
        int index = 1;
        for (CvExperience experience : profile.getExperiences()) {
            for (CvExperienceBullet bullet : experience.getBullets()) {
                if (bullet.getText() == null || bullet.getText().isBlank()) {
                    continue;
                }
                numbered.add(new NumberedBullet(index++, bullet.getId(), bullet.getText().strip()));
            }
        }
        return numbered;
    }

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(String jobDescription, List<NumberedBullet> bullets) {
        StringBuilder listing = new StringBuilder();
        for (NumberedBullet bullet : bullets) {
            listing.append(bullet.index()).append(". ").append(bullet.text()).append('\n');
        }
        return """
                JOB AD:
                %s

                CANDIDATE CV BULLETS:
                %s
                Return JSON exactly of this shape:
                {"requirements":[{"text":"<phrase copied verbatim from the ad>","bulletIds":[<numbers>]}],\
                "rankedBulletIds":[<relevant numbers, most relevant first>]}
                """.formatted(jobDescription, listing);
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringPromptBuilderTest
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: add selection result shape and tailoring prompt builder"
```

---

## Task 4: The selection guard

The highest-value tests in the slice. Pure functions; this is what makes fabrication impossible.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/select/SelectionGuard.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/select/SelectionGuardTest.java`

**Interfaces:**
- Consumes: `SelectionResult` (Task 3)
- Produces:
  - `SelectionGuard.check(SelectionResult, String jobDescription, int bulletCount, int maxBulletsPerRequirement) : GuardVerdict`
  - `GuardVerdict.accepted() : boolean`, `.violations() : List<String>`, `.isOverBroad(int requirementIndex) : boolean`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/select/SelectionGuardTest.java`:

```java
package se.caiowain.jobseeker.tailor.select;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionGuardTest {

    private final SelectionGuard guard = new SelectionGuard();

    private static final String AD = """
            Har du en bakgrund inom webbutveckling och brinner för mötet mellan teknik och människor?
            Vi söker en Partner Engineer. Har erfarenhet av webbutveckling.
            Har god förståelse API-design. Har mycket god engelska i tal och skrift.
            """;

    @Test
    void acceptsTheRealSpikeResponse() {
        // Exactly what qwen2.5:7b-instruct returned during the design spike.
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1)),
                new RequirementSelection("Har god förståelse API-design", List.of(1, 3))),
                List.of(1, 3, 2));

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.violations()).isEmpty();
    }

    @Test
    void rejectsABulletNumberThatDoesNotExist() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(99))),
                List.of(99));

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.violations().toString()).contains("99");
    }

    @Test
    void rejectsARequirementPhraseThatIsNotInTheAd() {
        // The Spike 1 failure mode: text lifted from nowhere, or invented outright.
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av 2nd line support till externa partners", List.of(1))),
                List.of(1));

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.violations().toString()).containsIgnoringCase("not found in the job ad");
    }

    @Test
    void acceptsPhrasesDifferingOnlyByCaseWhitespaceOrPunctuation() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("har   GOD förståelse, API-design!", List.of(1))),
                List.of(1));

        assertThat(guard.check(result, AD, 3, 3).accepted()).isTrue();
    }

    @Test
    void flagsAnOverBroadRequirementWithoutRejectingIt() {
        // Observed in the spike: one requirement matched to every bullet.
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 2, 3, 4))),
                List.of(1, 2, 3, 4));

        var verdict = guard.check(result, AD, 4, 3);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.isOverBroad(0)).isTrue();
    }

    @Test
    void anEmptyBulletListIsValid() {
        // "Nothing in your CV supports this" is a useful answer, not a violation.
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har mycket god engelska i tal och skrift", List.of())),
                List.of());

        var verdict = guard.check(result, AD, 3, 3);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.isOverBroad(0)).isFalse();
    }

    @Test
    void rejectsRankedIdsOutsideTheBulletRange() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1))),
                List.of(1, 7));

        assertThat(guard.check(result, AD, 3, 3).accepted()).isFalse();
    }

    @Test
    void rejectsANullOrEmptyResult() {
        assertThat(guard.check(new SelectionResult(null, null), AD, 3, 3).accepted()).isFalse();
        assertThat(guard.check(new SelectionResult(List.of(), List.of()), AD, 3, 3).accepted()).isFalse();
    }

    @Test
    void rejectsABlankRequirementPhrase() {
        SelectionResult result = new SelectionResult(List.of(
                new RequirementSelection("   ", List.of(1))), List.of(1));

        assertThat(guard.check(result, AD, 3, 3).accepted()).isFalse();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=SelectionGuardTest
```

Expected: compilation failure — `SelectionGuard` does not exist.

- [ ] **Step 3: Write `SelectionGuard`**

```java
package se.caiowain.jobseeker.tailor.select;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The three guards that make fabrication impossible.
 *
 * <p>Guard A — every bullet number must fall inside the 1..N range the model was shown.
 * Guard B — every requirement phrase must occur in the job ad, so it is a quotation rather
 * than a claim. Guard C — a requirement citing more bullets than the configured maximum is
 * flagged over-broad and rendered as a caution rather than as evidence.
 *
 * <p>A or B failing rejects the whole result. C never rejects: over-broad matching is
 * sloppy, not dishonest, and the reviewer is better served by seeing it flagged.
 */
public class SelectionGuard {

    public GuardVerdict check(SelectionResult result, String jobDescription,
                              int bulletCount, int maxBulletsPerRequirement) {
        List<String> violations = new ArrayList<>();
        Set<Integer> overBroad = new HashSet<>();

        if (result == null || result.requirements() == null || result.requirements().isEmpty()) {
            violations.add("The model returned no requirements");
            return new GuardVerdict(violations, overBroad);
        }

        String haystack = fold(jobDescription);

        for (int i = 0; i < result.requirements().size(); i++) {
            var requirement = result.requirements().get(i);

            String text = requirement.text();
            if (text == null || text.isBlank()) {
                violations.add("Requirement " + i + " has no text");
            } else if (!haystack.contains(fold(text))) {
                violations.add("Requirement " + i + " not found in the job ad: \"" + text.strip() + "\"");
            }

            List<Integer> ids = requirement.bulletIds() == null ? List.of() : requirement.bulletIds();
            for (Integer id : ids) {
                if (id == null || id < 1 || id > bulletCount) {
                    violations.add("Requirement " + i + " cites bullet " + id
                            + ", which is outside 1.." + bulletCount);
                }
            }
            if (ids.size() > maxBulletsPerRequirement) {
                overBroad.add(i);
            }
        }

        List<Integer> ranked = result.rankedBulletIds() == null ? List.of() : result.rankedBulletIds();
        for (Integer id : ranked) {
            if (id == null || id < 1 || id > bulletCount) {
                violations.add("Ranked bullet " + id + " is outside 1.." + bulletCount);
            }
        }

        return new GuardVerdict(violations, overBroad);
    }

    /** Lowercase, strip diacritics and punctuation, collapse whitespace. */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalised = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalised.toLowerCase()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    public record GuardVerdict(List<String> violations, Set<Integer> overBroadRequirements) {

        public boolean accepted() {
            return violations.isEmpty();
        }

        public boolean isOverBroad(int requirementIndex) {
            return overBroadRequirements.contains(requirementIndex);
        }
    }
}
```

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=SelectionGuardTest
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat: add selection guard preventing fabricated claims"
```

---

## Task 5: The application assembler

Pure. Turns a validated `SelectionResult` into entities built from profile rows.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/ApplicationAssembler.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/ApplicationAssemblerTest.java`

**Interfaces:**
- Consumes: `SelectionResult`, `GuardVerdict` (Task 4), `NumberedBullet` (Task 3)
- Produces: `ApplicationAssembler.assemble(SelectionResult, SelectionGuard.GuardVerdict, List<NumberedBullet>, JobPosting, CvProfile, String modelUsed, String rawOutput) : TailoredApplication`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/ApplicationAssemblerTest.java`:

```java
package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.select.NumberedBullet;
import se.caiowain.jobseeker.tailor.select.SelectionGuard;
import se.caiowain.jobseeker.tailor.select.SelectionResult;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationAssemblerTest {

    private final ApplicationAssembler assembler = new ApplicationAssembler();

    private final List<NumberedBullet> bullets = List.of(
            new NumberedBullet(1, 101L, "Built REST APIs in Java and Spring Boot."),
            new NumberedBullet(2, 102L, "Reduced deployment time from hours to minutes."),
            new NumberedBullet(3, 103L, "Ran the public developer API and its documentation."));

    private SelectionGuard.GuardVerdict clean() {
        return new SelectionGuard.GuardVerdict(List.of(), Set.of());
    }

    private TailoredApplication assemble(SelectionResult result, SelectionGuard.GuardVerdict verdict) {
        return assembler.assemble(result, verdict, bullets, new JobPosting(), new CvProfile(),
                "qwen2.5:7b-instruct", "{}");
    }

    @Test
    void everyAssembledBulletIsByteIdenticalToAProfileBullet() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 3))),
                List.of(1, 3));

        TailoredApplication app = assemble(result, clean());

        var evidence = app.getRequirements().getFirst().getEvidence();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).getBulletText()).isEqualTo("Built REST APIs in Java and Spring Boot.");
        assertThat(evidence.get(1).getBulletText())
                .isEqualTo("Ran the public developer API and its documentation.");
    }

    @Test
    void evidenceCarriesTheRealProfileBulletId() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(3))), List.of(3));

        assertThat(assemble(result, clean()).getRequirements().getFirst()
                .getEvidence().getFirst().getCvExperienceBulletId()).isEqualTo(103L);
    }

    @Test
    void coverageIsRequirementsWithEvidenceOverTotal() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1)),
                new RequirementSelection("Har god förståelse API-design", List.of()),
                new RequirementSelection("Har mycket god engelska i tal och skrift", List.of(2)),
                new RequirementSelection("Har en avslutad eftergymnasial utbildning", List.of())),
                List.of(1, 2));

        assertThat(assemble(result, clean()).getCoveragePercent()).isEqualTo(50);
    }

    @Test
    void coverageIsZeroWhenNothingMatches() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of())), List.of());

        assertThat(assemble(result, clean()).getCoveragePercent()).isZero();
    }

    @Test
    void overBroadRequirementsAreMarked() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 2, 3))),
                List.of(1, 2, 3));

        var verdict = new SelectionGuard.GuardVerdict(List.of(), Set.of(0));

        assertThat(assemble(result, verdict).getRequirements().getFirst().isOverBroad()).isTrue();
    }

    @Test
    void unknownBulletNumbersAreSkippedRatherThanInvented() {
        // The guard rejects these upstream; the assembler must never fabricate a fallback.
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 99))),
                List.of(1));

        assertThat(assemble(result, clean()).getRequirements().getFirst().getEvidence()).hasSize(1);
    }

    @Test
    void requirementOrderAndStatusAreSet() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1)),
                new RequirementSelection("Har god förståelse API-design", List.of(3))),
                List.of(1, 3));

        TailoredApplication app = assemble(result, clean());

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app.getModelUsed()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(app.getGeneratedAt()).isNotNull();
        assertThat(app.getRequirements().get(0).getOrdinal()).isZero();
        assertThat(app.getRequirements().get(1).getOrdinal()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=ApplicationAssemblerTest
```

Expected: compilation failure — `ApplicationAssembler` does not exist.

- [ ] **Step 3: Write `ApplicationAssembler`**

```java
package se.caiowain.jobseeker.tailor;

import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.ApplicationEvidence;
import se.caiowain.jobseeker.tailor.domain.ApplicationRequirement;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.select.NumberedBullet;
import se.caiowain.jobseeker.tailor.select.SelectionGuard;
import se.caiowain.jobseeker.tailor.select.SelectionResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the application from profile rows. Pure and deterministic.
 *
 * <p>Every string that reaches the employer originates here, from a {@link NumberedBullet}
 * the candidate wrote or a requirement phrase quoted from the ad. The model's integers only
 * choose and order; they never supply text.
 */
public class ApplicationAssembler {

    public TailoredApplication assemble(SelectionResult result,
                                        SelectionGuard.GuardVerdict verdict,
                                        List<NumberedBullet> bullets,
                                        JobPosting job,
                                        CvProfile profile,
                                        String modelUsed,
                                        String rawOutput) {
        Map<Integer, NumberedBullet> byIndex = new LinkedHashMap<>();
        for (NumberedBullet bullet : bullets) {
            byIndex.put(bullet.index(), bullet);
        }

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);
        application.setCvProfile(profile);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setModelUsed(modelUsed);
        application.setGeneratedAt(Instant.now());
        application.setRawModelOutput(rawOutput);

        List<SelectionResult.RequirementSelection> selections =
                result.requirements() == null ? List.of() : result.requirements();

        int withEvidence = 0;
        for (int i = 0; i < selections.size(); i++) {
            var selection = selections.get(i);

            ApplicationRequirement requirement = new ApplicationRequirement();
            requirement.setText(selection.text());
            requirement.setOrdinal(i);
            requirement.setOverBroad(verdict.isOverBroad(i));
            application.addRequirement(requirement);

            List<Integer> ids = selection.bulletIds() == null ? List.of() : selection.bulletIds();
            int ordinal = 0;
            for (Integer id : ids) {
                NumberedBullet bullet = byIndex.get(id);
                if (bullet == null) {
                    // Never fabricate a fallback. The guard rejects this upstream; if one
                    // slips through, dropping it is the only honest response.
                    continue;
                }
                ApplicationEvidence evidence = new ApplicationEvidence();
                evidence.setCvExperienceBulletId(bullet.bulletId());
                evidence.setBulletText(bullet.text());
                evidence.setOrdinal(ordinal++);
                requirement.addEvidence(evidence);
            }
            if (!requirement.getEvidence().isEmpty()) {
                withEvidence++;
            }
        }

        application.setCoveragePercent(
                selections.isEmpty() ? 0 : Math.round(withEvidence * 100f / selections.size()));
        return application;
    }
}
```

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=ApplicationAssemblerTest
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat: add application assembler building only from profile rows"
```

---

## Task 6: Ollama client and tailoring service

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/select/OllamaSelectionClient.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/{TailoringService,TailoringConfig,TailoringUnavailableException,ProfileNotReadyException,TailoringRejectedException}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/TailoringServiceTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–5
- Produces:
  - `OllamaSelectionClient.select(String jobDescription, List<NumberedBullet>) : SelectionResult`
  - `OllamaSelectionClient.isAvailable() : boolean`, `.modelName() : String`
  - `TailoringService.tailor(Long jobId) : TailoredApplication`
  - `TailoringService.approve(Long id)`, `.discard(Long id)`, `.saveLetter(Long id, String prose)`
  - `TailoringConfig` exposes `SelectionGuard`, `ApplicationAssembler`, `TailoringPromptBuilder` as beans

- [ ] **Step 1: Write the exceptions and the bean config**

```java
// tailor/TailoringUnavailableException.java
package se.caiowain.jobseeker.tailor;

/** No local model is configured or reachable. Maps to HTTP 503. */
public class TailoringUnavailableException extends RuntimeException {
    public TailoringUnavailableException(String message) { super(message); }
}
```

```java
// tailor/ProfileNotReadyException.java
package se.caiowain.jobseeker.tailor;

/** Tailoring needs an approved CV profile. Maps to HTTP 409. */
public class ProfileNotReadyException extends RuntimeException {
    public ProfileNotReadyException(String message) { super(message); }
}
```

```java
// tailor/TailoringRejectedException.java
package se.caiowain.jobseeker.tailor;

import java.util.List;

/** The model's output failed the guards twice. Maps to HTTP 422. */
public class TailoringRejectedException extends RuntimeException {
    private final List<String> violations;

    public TailoringRejectedException(String message, List<String> violations) {
        super(message);
        this.violations = violations;
    }

    public List<String> getViolations() { return violations; }
}
```

```java
// tailor/TailoringConfig.java
package se.caiowain.jobseeker.tailor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.caiowain.jobseeker.tailor.select.SelectionGuard;
import se.caiowain.jobseeker.tailor.select.TailoringPromptBuilder;

@Configuration
public class TailoringConfig {

    /** Kept free of Spring annotations so their tests stay pure. */
    @Bean public SelectionGuard selectionGuard() { return new SelectionGuard(); }
    @Bean public ApplicationAssembler applicationAssembler() { return new ApplicationAssembler(); }
    @Bean public TailoringPromptBuilder tailoringPromptBuilder() { return new TailoringPromptBuilder(); }
}
```

- [ ] **Step 2: Write `OllamaSelectionClient`**

```java
package se.caiowain.jobseeker.tailor.select;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.tailor.TailoringUnavailableException;

import java.util.List;

/**
 * The only component in the tailoring slice that calls a model.
 *
 * <p>The model is configured in application.yml, never here: the Ollama options builder
 * accepts only the {@code OllamaModel} enum, which does not contain qwen2.5:7b-instruct.
 */
@Component
public class OllamaSelectionClient {

    private final ChatClient.Builder chatClientBuilder;
    private final TailoringPromptBuilder prompts;
    private final String model;
    private final String baseUrl;

    public OllamaSelectionClient(ChatClient.Builder chatClientBuilder,
                                 TailoringPromptBuilder prompts,
                                 @Value("${spring.ai.ollama.chat.options.model:}") String model,
                                 @Value("${spring.ai.ollama.base-url:}") String baseUrl) {
        this.chatClientBuilder = chatClientBuilder;
        this.prompts = prompts;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    public boolean isAvailable() {
        return model != null && !model.isBlank();
    }

    public String modelName() {
        return model;
    }

    public SelectionResult select(String jobDescription, List<NumberedBullet> bullets) {
        if (!isAvailable()) {
            throw new TailoringUnavailableException(
                    "Tailoring needs a local model. Start Ollama and set "
                            + "spring.ai.ollama.chat.options.model.");
        }
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(prompts.systemPrompt())
                    .user(prompts.userPrompt(jobDescription, bullets))
                    .options(OllamaChatOptions.builder().format("json"))
                    .call()
                    .entity(SelectionResult.class);
        } catch (Exception e) {
            throw new TailoringUnavailableException(
                    "Could not reach the local model at " + baseUrl + " (" + model + "): "
                            + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Write the failing service test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/TailoringServiceTest.java`:

```java
package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;
import se.caiowain.jobseeker.tailor.select.NumberedBullet;
import se.caiowain.jobseeker.tailor.select.OllamaSelectionClient;
import se.caiowain.jobseeker.tailor.select.SelectionResult;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
class TailoringServiceTest extends AbstractIntegrationTest {

    @Autowired TailoringService service;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    /** The only model caller is replaced; no test reaches the network. */
    @MockitoBean OllamaSelectionClient selectionClient;

    private static final String DESCRIPTION =
            "Vi soker en utvecklare. Har erfarenhet av webbutveckling. Har god forstaelse API-design.";

    private Long jobId;
    private CvProfile profile;

    @BeforeEach
    void seed() {
        applications.deleteAll();
        profiles.deleteAll();
        documents.deleteAll();

        JobPosting job = new JobPosting();
        job.setFingerprint("fp-svc-" + System.nanoTime());
        job.setCanonicalUrl("https://example.se/j");
        job.setTitle("Utvecklare");
        job.setEmployerName("Acme AB");
        job.setDescription(DESCRIPTION);
        job.setAtsVendor(AtsVendor.OTHER);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        jobId = jobs.save(job).getId();

        profile = readyProfile();

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());
    }

    private CvProfile readyProfile() {
        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1L);
        doc.setSha256("sha-svc-" + System.nanoTime());
        doc.setContent(new byte[]{1});
        doc.setExtractedText("Built REST APIs in Java and Spring Boot.");
        doc.setUploadedAt(Instant.now());
        documents.save(doc);

        CvProfile p = new CvProfile();
        p.setCvDocument(doc);
        p.setFullName("Cai Wain");
        p.setStatus(ProfileStatus.READY);

        CvExperience exp = new CvExperience();
        exp.setEmployer("Nordic Systems AB");
        exp.setTitle("Senior Software Engineer");
        exp.setOrdinal(0);
        p.addExperience(exp);

        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built REST APIs in Java and Spring Boot.");
        bullet.setOrdinal(0);
        exp.addBullet(bullet);

        return profiles.save(p);
    }

    @Test
    void tailorProducesADraftBuiltFromProfileBullets() {
        TailoredApplication app = service.tailor(jobId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app.getRequirements()).hasSize(1);
        assertThat(app.getRequirements().getFirst().getEvidence().getFirst().getBulletText())
                .isEqualTo("Built REST APIs in Java and Spring Boot.");
        assertThat(app.getCoveragePercent()).isEqualTo(100);
    }

    @Test
    void refusesWhenNoProfileIsReady() {
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(profile);

        assertThatThrownBy(() -> service.tailor(jobId))
                .isInstanceOf(ProfileNotReadyException.class);
    }

    @Test
    void retriesOnceThenRejectsAFabricatedRequirement() {
        // The Spike 1 failure mode: a phrase that is not in the ad.
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av 2nd line support", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());

        assertThatThrownBy(() -> service.tailor(jobId))
                .isInstanceOf(TailoringRejectedException.class);

        verify(selectionClient, times(2)).select(anyString(), any());
        assertThat(applications.findFirstByJobPostingIdOrderByIdDesc(jobId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.GENERATION_FAILED);
    }

    @Test
    void aSecondAttemptThatPassesIsAccepted() {
        doReturn(new SelectionResult(List.of(
                        new RequirementSelection("not in the ad at all", List.of(1))), List.of(1)))
                .doReturn(new SelectionResult(List.of(
                        new RequirementSelection("Har god forstaelse API-design", List.of(1))), List.of(1)))
                .when(selectionClient).select(anyString(), any());

        TailoredApplication app = service.tailor(jobId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        verify(selectionClient, times(2)).select(anyString(), any());
    }

    @Test
    void reTailoringReplacesADraft() {
        service.tailor(jobId);
        service.tailor(jobId);

        assertThat(applications.count()).isEqualTo(1);
    }

    @Test
    void reTailoringRefusesToOverwriteAnApprovedApplication() {
        TailoredApplication app = service.tailor(jobId);
        service.approve(app.getId());

        assertThatThrownBy(() -> service.tailor(jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Discard");
    }

    @Test
    void approveFreezesAndDiscardReleases() {
        TailoredApplication app = service.tailor(jobId);

        service.approve(app.getId());
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.APPROVED);

        service.discard(app.getId());
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.DISCARDED);

        // Once discarded, re-tailoring is allowed again.
        assertThat(service.tailor(jobId).getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void savesTheProseYouWrote() {
        TailoredApplication app = service.tailor(jobId);

        service.saveLetter(app.getId(), "Hej! Jag söker tjänsten eftersom...");

        assertThat(applications.findById(app.getId()).orElseThrow().getLetterProse())
                .startsWith("Hej!");
    }
}
```

- [ ] **Step 4: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringServiceTest
```

Expected: compilation failure — `TailoringService` does not exist.

- [ ] **Step 5: Write `TailoringService`**

```java
package se.caiowain.jobseeker.tailor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;
import se.caiowain.jobseeker.tailor.select.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class TailoringService {

    private static final Logger log = LoggerFactory.getLogger(TailoringService.class);

    private final TailoredApplicationRepository applications;
    private final JobPostingRepository jobs;
    private final CvProfileRepository profiles;
    private final OllamaSelectionClient selectionClient;
    private final TailoringPromptBuilder prompts;
    private final SelectionGuard guard;
    private final ApplicationAssembler assembler;
    private final int maxBulletsPerRequirement;

    public TailoringService(TailoredApplicationRepository applications,
                            JobPostingRepository jobs,
                            CvProfileRepository profiles,
                            OllamaSelectionClient selectionClient,
                            TailoringPromptBuilder prompts,
                            SelectionGuard guard,
                            ApplicationAssembler assembler,
                            @Value("${jobseeker.tailor.max-bullets-per-requirement:3}") int maxBulletsPerRequirement) {
        this.applications = applications;
        this.jobs = jobs;
        this.profiles = profiles;
        this.selectionClient = selectionClient;
        this.prompts = prompts;
        this.guard = guard;
        this.assembler = assembler;
        this.maxBulletsPerRequirement = maxBulletsPerRequirement;
    }

    /**
     * {@code noRollbackFor} is load-bearing: the failure path saves a GENERATION_FAILED
     * record and then throws. Under the default rollback-on-RuntimeException rule that
     * save would be discarded, and the failure would vanish instead of being debuggable.
     */
    @Transactional(noRollbackFor = TailoringRejectedException.class)
    public TailoredApplication tailor(Long jobId) {
        JobPosting job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));

        CvProfile profile = profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .orElseThrow(() -> new ProfileNotReadyException(
                        "Approve your CV profile before tailoring an application."));

        Optional<TailoredApplication> existing =
                applications.findByJobPostingIdAndCvProfileId(jobId, profile.getId());
        if (existing.isPresent()) {
            if (existing.get().getStatus() == ApplicationStatus.APPROVED) {
                throw new IllegalStateException(
                        "This job already has an approved application. Discard it first.");
            }
            applications.delete(existing.get());
            applications.flush();
        }

        List<NumberedBullet> bullets = prompts.numberBullets(profile);
        if (bullets.isEmpty()) {
            throw new ProfileNotReadyException(
                    "Your profile has no experience bullets to match against.");
        }

        SelectionGuard.GuardVerdict lastVerdict = null;
        SelectionResult lastResult = null;

        // One retry: small models occasionally quote loosely on the first pass.
        for (int attempt = 1; attempt <= 2; attempt++) {
            SelectionResult result = selectionClient.select(job.getDescription(), bullets);
            SelectionGuard.GuardVerdict verdict =
                    guard.check(result, job.getDescription(), bullets.size(), maxBulletsPerRequirement);
            lastResult = result;
            lastVerdict = verdict;

            if (verdict.accepted()) {
                TailoredApplication application = assembler.assemble(
                        result, verdict, bullets, job, profile,
                        selectionClient.modelName(), String.valueOf(result));
                return applications.save(application);
            }
            log.warn("Tailoring attempt {} rejected for job {}: {}",
                    attempt, jobId, verdict.violations());
        }

        TailoredApplication failed = new TailoredApplication();
        failed.setJobPosting(job);
        failed.setCvProfile(profile);
        failed.setStatus(ApplicationStatus.GENERATION_FAILED);
        failed.setModelUsed(selectionClient.modelName());
        failed.setGeneratedAt(Instant.now());
        failed.setRawModelOutput(String.valueOf(lastResult));
        applications.save(failed);
        applications.flush();

        throw new TailoringRejectedException(
                "The model's selection failed validation twice.", lastVerdict.violations());
    }

    @Transactional
    public TailoredApplication approve(Long id) {
        TailoredApplication app = require(id);
        app.setStatus(ApplicationStatus.APPROVED);
        app.setReviewedAt(Instant.now());
        return applications.save(app);
    }

    @Transactional
    public TailoredApplication discard(Long id) {
        TailoredApplication app = require(id);
        app.setStatus(ApplicationStatus.DISCARDED);
        return applications.save(app);
    }

    @Transactional
    public TailoredApplication saveLetter(Long id, String prose) {
        TailoredApplication app = require(id);
        app.setLetterProse(prose);
        return applications.save(app);
    }

    private TailoredApplication require(Long id) {
        return applications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No application with id " + id));
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringServiceTest
```

Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: add Ollama selection client and tailoring service"
```

---

## Task 7: Tailoring REST API

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/api/TailoringController.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/api/TailoringExceptionHandler.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/tailor/api/dto/{ApplicationDto,ApplicationSummaryDto,RequirementDto,EvidenceDto,LetterRequest}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/tailor/api/TailoringControllerTest.java`

**Interfaces:**
- Consumes: `TailoringService`, `TailoredApplicationRepository`
- Produces: the seven endpoints from the spec

- [ ] **Step 1: Write the DTOs**

```java
// tailor/api/dto/EvidenceDto.java
package se.caiowain.jobseeker.tailor.api.dto;

public record EvidenceDto(Long id, Long cvExperienceBulletId, String bulletText, int ordinal) {
}
```

```java
// tailor/api/dto/RequirementDto.java
package se.caiowain.jobseeker.tailor.api.dto;

import java.util.List;

public record RequirementDto(Long id, String text, int ordinal, boolean overBroad,
                             List<EvidenceDto> evidence) {
}
```

```java
// tailor/api/dto/ApplicationDto.java
package se.caiowain.jobseeker.tailor.api.dto;

import java.time.Instant;
import java.util.List;

public record ApplicationDto(Long id, Long jobId, String jobTitle, String employerName,
                             String jobApplyUrl, String status, String modelUsed,
                             int coveragePercent, Instant generatedAt, Instant reviewedAt,
                             String letterProse, List<RequirementDto> requirements) {
}
```

```java
// tailor/api/dto/ApplicationSummaryDto.java
package se.caiowain.jobseeker.tailor.api.dto;

import java.time.Instant;

public record ApplicationSummaryDto(Long id, Long jobId, String jobTitle, String employerName,
                                    String status, int coveragePercent, Instant generatedAt) {
}
```

```java
// tailor/api/dto/LetterRequest.java
package se.caiowain.jobseeker.tailor.api.dto;

public record LetterRequest(String prose) {
}
```

- [ ] **Step 2: Write the exception handler**

```java
package se.caiowain.jobseeker.tailor.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import se.caiowain.jobseeker.tailor.ProfileNotReadyException;
import se.caiowain.jobseeker.tailor.TailoringRejectedException;
import se.caiowain.jobseeker.tailor.TailoringUnavailableException;

@RestControllerAdvice
public class TailoringExceptionHandler {

    @ExceptionHandler(ProfileNotReadyException.class)
    ProblemDetail notReady(ProfileNotReadyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(TailoringUnavailableException.class)
    ProblemDetail unavailable(TailoringUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** The guards rejected the model twice. 422: the request was fine, the output was not. */
    @ExceptionHandler(TailoringRejectedException.class)
    ProblemDetail rejected(TailoringRejectedException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        detail.setProperty("violations", e.getViolations());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail notFound(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
```

- [ ] **Step 3: Write the failing controller test**

`backend/src/test/java/se/caiowain/jobseeker/tailor/api/TailoringControllerTest.java`:

```java
package se.caiowain.jobseeker.tailor.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;
import se.caiowain.jobseeker.tailor.select.OllamaSelectionClient;
import se.caiowain.jobseeker.tailor.select.SelectionResult;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TailoringControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean OllamaSelectionClient selectionClient;

    private static final String DESCRIPTION =
            "Vi soker en utvecklare. Har erfarenhet av webbutveckling.";

    private Long jobId;

    @BeforeEach
    void seed() {
        applications.deleteAll();
        profiles.deleteAll();
        documents.deleteAll();

        JobPosting job = new JobPosting();
        job.setFingerprint("fp-api-" + System.nanoTime());
        job.setCanonicalUrl("https://example.se/j");
        job.setTitle("Utvecklare");
        job.setEmployerName("Acme AB");
        job.setDescription(DESCRIPTION);
        job.setApplyUrl("https://example.se/apply");
        job.setAtsVendor(AtsVendor.OTHER);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        jobId = jobs.save(job).getId();

        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1L);
        doc.setSha256("sha-api-" + System.nanoTime());
        doc.setContent(new byte[]{1});
        doc.setExtractedText("Built REST APIs.");
        doc.setUploadedAt(Instant.now());
        documents.save(doc);

        CvProfile p = new CvProfile();
        p.setCvDocument(doc);
        p.setFullName("Cai Wain");
        p.setStatus(ProfileStatus.READY);
        CvExperience exp = new CvExperience();
        exp.setEmployer("Nordic Systems AB");
        exp.setOrdinal(0);
        p.addExperience(exp);
        CvExperienceBullet b = new CvExperienceBullet();
        b.setText("Built REST APIs in Java and Spring Boot.");
        b.setOrdinal(0);
        exp.addBullet(b);
        profiles.save(p);

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());
    }

    @Test
    void tailorReturnsTheApplication() throws Exception {
        mvc.perform(post("/api/jobs/" + jobId + "/tailor"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.coveragePercent").value(100))
                .andExpect(jsonPath("$.requirements[0].text").value("Har erfarenhet av webbutveckling"))
                .andExpect(jsonPath("$.requirements[0].evidence[0].bulletText")
                        .value("Built REST APIs in Java and Spring Boot."));
    }

    @Test
    void returns404ForAnUnknownJob() throws Exception {
        mvc.perform(post("/api/jobs/999999/tailor")).andExpect(status().isNotFound());
    }

    @Test
    void returns409WhenNoProfileIsReady() throws Exception {
        CvProfile p = profiles.findFirstByOrderByIdDesc().orElseThrow();
        p.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(p);

        mvc.perform(post("/api/jobs/" + jobId + "/tailor")).andExpect(status().isConflict());
    }

    @Test
    void returns422WithViolationsWhenTheGuardRejects() throws Exception {
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("a phrase that is nowhere in the ad", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());

        mvc.perform(post("/api/jobs/" + jobId + "/tailor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void getsTheApplicationForAJobAnd404sWhenAbsent() throws Exception {
        mvc.perform(get("/api/jobs/" + jobId + "/application")).andExpect(status().isNotFound());

        mvc.perform(post("/api/jobs/" + jobId + "/tailor")).andExpect(status().isCreated());

        mvc.perform(get("/api/jobs/" + jobId + "/application"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Utvecklare"));
    }

    @Test
    void listsTheQueue() throws Exception {
        mvc.perform(post("/api/jobs/" + jobId + "/tailor")).andExpect(status().isCreated());

        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employerName").value("Acme AB"));
    }

    @Test
    void savesProseApprovesAndDiscards() throws Exception {
        String body = mvc.perform(post("/api/jobs/" + jobId + "/tailor"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Integer.class).longValue();

        mvc.perform(put("/api/applications/" + id + "/letter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prose\":\"Hej! Jag soker tjansten.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letterProse").value("Hej! Jag soker tjansten."));

        mvc.perform(post("/api/applications/" + id + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(delete("/api/applications/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
    }
}
```

- [ ] **Step 4: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringControllerTest
```

Expected: 404s / compilation failure — the controller does not exist.

- [ ] **Step 5: Write `TailoringController`**

```java
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

    @PostMapping("/api/applications/{id}/approve")
    public ApplicationDto approve(@PathVariable Long id) {
        return toDto(service.approve(id));
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
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TailoringControllerTest
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
cd /home/cai/Projects/AgenticJobSeeker
git add backend/src
git commit -m "feat: add tailoring REST API with typed error responses"
```

---

## Task 8: Frontend types, client and the tailor button

**Files:**
- Create: `frontend/src/applicationTypes.ts`, `frontend/src/api/applicationClient.ts`
- Modify: `frontend/src/App.tsx`, `frontend/src/pages/JobDetail.tsx`, `frontend/src/pages/JobList.tsx`

**Interfaces:**
- Consumes: the REST API from Task 7
- Produces: `tailorJob`, `fetchApplicationForJob`, `fetchApplications`, `fetchApplication`, `saveLetter`, `approveApplication`, `discardApplication`; routes `/applications` and `/applications/:id`

- [ ] **Step 1: Write the types**

`frontend/src/applicationTypes.ts`:

```ts
export type ApplicationStatus = 'DRAFT' | 'APPROVED' | 'DISCARDED' | 'GENERATION_FAILED'

export interface Evidence {
  id: number
  cvExperienceBulletId: number | null
  bulletText: string
  ordinal: number
}

export interface Requirement {
  id: number
  text: string
  ordinal: number
  overBroad: boolean
  evidence: Evidence[]
}

export interface Application {
  id: number
  jobId: number
  jobTitle: string
  employerName: string | null
  jobApplyUrl: string | null
  status: ApplicationStatus
  modelUsed: string | null
  coveragePercent: number
  generatedAt: string
  reviewedAt: string | null
  letterProse: string | null
  requirements: Requirement[]
}

export interface ApplicationSummary {
  id: number
  jobId: number
  jobTitle: string
  employerName: string | null
  status: ApplicationStatus
  coveragePercent: number
  generatedAt: string
}
```

- [ ] **Step 2: Write the API client**

`frontend/src/api/applicationClient.ts`:

```ts
import type { Application, ApplicationSummary } from '../applicationTypes'
import type { Page } from '../types'

async function readError(response: Response): Promise<string> {
  try {
    const body = await response.json()
    const violations = Array.isArray(body.violations) ? ` (${body.violations.join('; ')})` : ''
    return (body.detail ?? body.message ?? `${response.status} ${response.statusText}`) + violations
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<T>
}

/** Generation runs a local model and takes one to two minutes. */
export function tailorJob(jobId: number): Promise<Application> {
  return json<Application>(`/api/jobs/${jobId}/tailor`, { method: 'POST' })
}

/** Resolves to null when the job has no application yet — a 404 here is expected. */
export async function fetchApplicationForJob(jobId: number): Promise<Application | null> {
  const response = await fetch(`/api/jobs/${jobId}/application`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<Application>
}

export function fetchApplications(): Promise<Page<ApplicationSummary>> {
  return json<Page<ApplicationSummary>>('/api/applications')
}

export function fetchApplication(id: number): Promise<Application> {
  return json<Application>(`/api/applications/${id}`)
}

export function saveLetter(id: number, prose: string): Promise<Application> {
  return json<Application>(`/api/applications/${id}/letter`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prose }),
  })
}

export function approveApplication(id: number): Promise<Application> {
  return json<Application>(`/api/applications/${id}/approve`, { method: 'POST' })
}

export function discardApplication(id: number): Promise<Application> {
  return json<Application>(`/api/applications/${id}`, { method: 'DELETE' })
}
```

- [ ] **Step 3: Add the routes**

`frontend/src/App.tsx` — replace the whole file:

```tsx
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ApplicationQueue } from './pages/ApplicationQueue'
import { ApplicationReview } from './pages/ApplicationReview'
import { JobDetail } from './pages/JobDetail'
import { JobList } from './pages/JobList'
import { ProfilePage } from './pages/ProfilePage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<JobList />} />
        <Route path="/jobs/:id" element={<JobDetail />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/applications" element={<ApplicationQueue />} />
        <Route path="/applications/:id" element={<ApplicationReview />} />
      </Routes>
    </BrowserRouter>
  )
}
```

- [ ] **Step 4: Add the nav link on the job list**

In `frontend/src/pages/JobList.tsx`, replace the existing `<nav>` block with:

```tsx
      <nav className="mb-4 flex gap-4 text-sm">
        <span className="font-medium text-slate-900">Jobs</span>
        <Link to="/profile" className="text-slate-600 hover:underline">My CV profile</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
      </nav>
```

- [ ] **Step 5: Add the tailor button to the job detail page**

In `frontend/src/pages/JobDetail.tsx`, add these imports:

```tsx
import { fetchApplicationForJob, tailorJob } from '../api/applicationClient'
import type { Application } from '../applicationTypes'
import { useNavigate } from 'react-router-dom'
```

Add this state and effect inside the `JobDetail` component, after the existing `useEffect`:

```tsx
  const navigate = useNavigate()
  const [application, setApplication] = useState<Application | null>(null)
  const [tailoring, setTailoring] = useState(false)
  const [tailorError, setTailorError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchApplicationForJob(Number(id)).then(setApplication).catch(() => setApplication(null))
  }, [id])

  const runTailor = async () => {
    if (!id) return
    setTailoring(true)
    setTailorError(null)
    try {
      const created = await tailorJob(Number(id))
      navigate(`/applications/${created.id}`)
    } catch (e) {
      setTailorError((e as Error).message)
    } finally {
      setTailoring(false)
    }
  }
```

Insert this block directly after the "Apply on employer site" anchor:

```tsx
      <div className="mt-4">
        {application ? (
          <Link
            to={`/applications/${application.id}`}
            className="inline-block rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50"
          >
            View tailored application ({application.coveragePercent}% match)
          </Link>
        ) : (
          <button
            onClick={runTailor}
            disabled={tailoring}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
          >
            {tailoring ? 'Matching your CV… (1–2 min)' : 'Tailor for this job'}
          </button>
        )}
        {tailoring && (
          <p className="mt-2 text-xs text-slate-500">
            A local model is matching your CV bullets to this ad. Nothing leaves your machine.
          </p>
        )}
        {tailorError && (
          <p className="mt-2 rounded-md bg-red-50 p-3 text-sm text-red-700">{tailorError}</p>
        )}
      </div>
```

- [ ] **Step 6: Commit (build verified in Task 9, which adds the pages)**

```bash
git add frontend/src
git commit -m "feat: add application types, client and tailor action"
```

---

## Task 9: Application queue and review pages

**Files:**
- Create: `frontend/src/components/CoverageBar.tsx`, `frontend/src/pages/ApplicationQueue.tsx`, `frontend/src/pages/ApplicationReview.tsx`

**Interfaces:**
- Consumes: the client from Task 8
- Produces: the `/applications` and `/applications/:id` screens

- [ ] **Step 1: Write `CoverageBar`**

`frontend/src/components/CoverageBar.tsx`:

```tsx
export function CoverageBar({ percent }: { percent: number }) {
  const tone =
    percent >= 70 ? 'bg-emerald-500' : percent >= 40 ? 'bg-amber-500' : 'bg-red-500'

  return (
    <div className="flex items-center gap-3">
      <div className="h-2 w-40 overflow-hidden rounded-full bg-slate-200">
        <div className={`h-full ${tone}`} style={{ width: `${Math.min(100, percent)}%` }} />
      </div>
      <span className="text-sm font-medium text-slate-700">{percent}% of requirements evidenced</span>
    </div>
  )
}
```

- [ ] **Step 2: Write `ApplicationQueue`**

`frontend/src/pages/ApplicationQueue.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchApplications } from '../api/applicationClient'
import type { ApplicationSummary } from '../applicationTypes'
import type { Page } from '../types'

const STATUS_TONE: Record<string, string> = {
  DRAFT: 'bg-slate-100 text-slate-700',
  APPROVED: 'bg-emerald-100 text-emerald-800',
  DISCARDED: 'bg-slate-100 text-slate-500',
  GENERATION_FAILED: 'bg-red-100 text-red-800',
}

export function ApplicationQueue() {
  const [data, setData] = useState<Page<ApplicationSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchApplications().then(setData).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/profile" className="text-slate-600 hover:underline">My CV profile</Link>
        <span className="font-medium text-slate-900">Applications</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Applications</h1>
      <p className="mt-1 mb-6 text-sm text-slate-600">
        Every line of these is assembled from sentences you wrote.
      </p>

      {error && <p className="rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

      {data && data.content.length === 0 && (
        <p className="py-12 text-center text-sm text-slate-500">
          No applications yet. Open a job and choose “Tailor for this job”.
        </p>
      )}

      <ul className="divide-y divide-slate-200">
        {data?.content.map((a) => (
          <li key={a.id} className="flex items-center justify-between gap-4 py-4">
            <div className="min-w-0">
              <Link to={`/applications/${a.id}`} className="font-medium text-slate-900 hover:underline">
                {a.jobTitle}
              </Link>
              <p className="mt-0.5 truncate text-sm text-slate-600">{a.employerName ?? 'Unknown employer'}</p>
            </div>
            <div className="flex shrink-0 items-center gap-3">
              <span className="text-sm text-slate-600">{a.coveragePercent}%</span>
              <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${STATUS_TONE[a.status] ?? ''}`}>
                {a.status}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
```

- [ ] **Step 3: Write `ApplicationReview`**

`frontend/src/pages/ApplicationReview.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  approveApplication, discardApplication, fetchApplication, saveLetter,
} from '../api/applicationClient'
import type { Application } from '../applicationTypes'
import { CoverageBar } from '../components/CoverageBar'

export function ApplicationReview() {
  const { id } = useParams<{ id: string }>()
  const [app, setApp] = useState<Application | null>(null)
  const [prose, setProse] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchApplication(Number(id))
      .then((a) => { setApp(a); setProse(a.letterProse ?? '') })
      .catch((e: Error) => setError(e.message))
  }, [id])

  const run = async (action: () => Promise<Application>, note: string) => {
    setBusy(true); setError(null); setMessage(null)
    try { setApp(await action()); setMessage(note) }
    catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  if (error && !app) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-red-700">{error}</p>
  }
  if (!app) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-slate-500">Loading…</p>
  }

  const unmatched = app.requirements.filter((r) => r.evidence.length === 0)

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
        <span className="font-medium text-slate-900">{app.jobTitle}</span>
      </nav>

      <header className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{app.jobTitle}</h1>
          <p className="mt-1 text-sm text-slate-600">
            {app.employerName ?? 'Unknown employer'} · {app.status}
            {app.modelUsed ? ` · matched locally by ${app.modelUsed}` : ''}
          </p>
          <div className="mt-3"><CoverageBar percent={app.coveragePercent} /></div>
        </div>
        <div className="flex gap-2">
          <button onClick={() => run(() => saveLetter(app.id, prose), 'Saved.')} disabled={busy}
                  className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50">
            Save
          </button>
          <button onClick={() => run(() => discardApplication(app.id), 'Discarded.')} disabled={busy}
                  className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50">
            Discard
          </button>
          <button onClick={() => run(() => approveApplication(app.id), 'Approved.')} disabled={busy}
                  className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50">
            Approve
          </button>
        </div>
      </header>

      {message && <p className="mb-4 rounded-md bg-emerald-50 p-3 text-sm text-emerald-800">{message}</p>}
      {error && <p className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

      {unmatched.length > 0 && (
        <p className="mb-6 rounded-md bg-amber-50 p-3 text-sm text-amber-800">
          {unmatched.length} requirement{unmatched.length === 1 ? '' : 's'} had nothing in your CV to
          support {unmatched.length === 1 ? 'it' : 'them'}. That gap is the most useful thing on this page.
        </p>
      )}

      <section className="mb-8">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
          What they ask for, and what you have
        </h2>
        <ul className="space-y-3">
          {app.requirements.map((r) => (
            <li key={r.id}
                className={`rounded-md border p-3 ${
                  r.evidence.length === 0 ? 'border-amber-400 bg-amber-50' : 'border-slate-200'
                }`}>
              <p className="text-sm font-medium text-slate-900">{r.text}</p>
              {r.overBroad && (
                <p className="mt-1 text-xs text-amber-700">
                  The model cited a lot of bullets here — check the match is real.
                </p>
              )}
              {r.evidence.length === 0 ? (
                <p className="mt-2 text-sm text-amber-800">Nothing in your CV covers this.</p>
              ) : (
                <ul className="mt-2 list-disc space-y-1 pl-5">
                  {r.evidence.map((e) => (
                    <li key={e.id} className="text-sm text-slate-700">{e.bulletText}</li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Your cover letter
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          The evidence above is your raw material. The prose is yours — nothing here was written for you.
        </p>
        <textarea
          value={prose}
          onChange={(e) => setProse(e.target.value)}
          rows={14}
          placeholder="Write your letter here, using the matched bullets above as evidence."
          className="w-full rounded-md border border-slate-300 px-3 py-2 font-mono text-sm outline-none focus:border-slate-900"
        />
      </section>
    </div>
  )
}
```

- [ ] **Step 4: Build and typecheck**

```bash
cd frontend && npm run build
```

Expected: build succeeds with no TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat: add application queue and review pages"
```

---

## Task 10: End-to-end verification and documentation

**Files:**
- Modify: `README.md`
- Test: manual verification against the running system

- [ ] **Step 1: Run the full backend suite**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: all tests PASS with no network calls.

- [ ] **Step 2: Verify the degraded path first — Ollama stopped**

```bash
cd /home/cai/Projects/AgenticJobSeeker
docker compose up -d
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw spring-boot:run
```

In a second shell, with Ollama not running:

```bash
curl -s -o /dev/null -w "health: %{http_code}\n" http://localhost:8080/actuator/health
curl -s "http://localhost:8080/api/stats" | head -c 80; echo
curl -s -o /dev/null -w "tailor without profile: %{http_code} (expect 409)\n" \
  -X POST http://localhost:8080/api/jobs/1/tailor
```

Expected: `200`, Slice 1 intact, `409`. Slices 1 and 2a must be unaffected.

- [ ] **Step 3: Start Ollama and confirm the model is present**

```bash
export OLLAMA_MODELS=$HOME/.local/ollama/models
nohup ~/.local/ollama/bin/ollama serve > /tmp/ollama-serve.log 2>&1 &
sleep 5
curl -s http://localhost:11434/api/tags | python3 -m json.tool | grep '"name"'
```

Expected: `qwen2.5:7b-instruct` listed. If absent:
`~/.local/ollama/bin/ollama pull qwen2.5:7b-instruct`

- [ ] **Step 4: Create a profile to tailor from**

Extraction needs a real CV. Upload one:

```bash
curl -s -F "file=@<your CV>.pdf" http://localhost:8080/api/profile/upload | python3 -m json.tool | head -30
curl -s -X POST http://localhost:8080/api/profile/approve | python3 -c "
import json,sys; p=json.load(sys.stdin); print('profile status:', p['status'])"
```

Expected: `READY`. Extraction now runs on Ollama and takes one to two minutes.

- [ ] **Step 5: Tailor a real Swedish job and verify the guarantee**

Pick a Swedish posting and tailor it:

```bash
JOB=$(docker exec jobseeker-postgres psql -U jobseeker -d job_db -t -A -c \
  "SELECT id FROM job_posting WHERE language='sv' AND length(description) BETWEEN 1500 AND 3000 LIMIT 1;")
echo "job: $JOB"
time curl -s -X POST "http://localhost:8080/api/jobs/$JOB/tailor" | python3 -m json.tool | head -40
```

Expected: `DRAFT`, a coverage percentage, and requirements. Then verify the two guarantees
directly in the database:

```bash
# Every requirement must appear in that job's own description.
docker exec jobseeker-postgres psql -U jobseeker -d job_db -t -A -c "
  SELECT r.text, position(lower(r.text) in lower(j.description)) > 0 AS quoted_from_ad
  FROM application_requirement r
  JOIN tailored_application a ON a.id = r.tailored_application_id
  JOIN job_posting j ON j.id = a.job_posting_id;"

# Every evidence bullet must exist verbatim in the profile.
docker exec jobseeker-postgres psql -U jobseeker -d job_db -t -A -c "
  SELECT e.bullet_text = b.text AS matches_profile_bullet
  FROM application_evidence e
  LEFT JOIN cv_experience_bullet b ON b.id = e.cv_experience_bullet_id;"
```

Expected: every row `t`. Any `f` is a design violation and must be investigated before
this slice is considered done.

- [ ] **Step 6: Verify the guard rejects fabrication**

Confirm the retry-then-fail path is reachable by checking the log during a rejection, or by
confirming the unit test covers it:

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default \
  && ./mvnw -B test -Dtest=SelectionGuardTest,TailoringServiceTest
```

Expected: PASS, including `rejectsARequirementPhraseThatIsNotInTheAd` and
`retriesOnceThenRejectsAFabricatedRequirement`.

- [ ] **Step 7: Verify the UI**

```bash
cd frontend && npm run dev
```

Open `http://localhost:5173`, click a job, choose "Tailor for this job", and confirm the
review screen shows requirements with evidence, unmatched requirements in amber, a coverage
bar, and an editable letter box. Approve, then confirm re-tailoring that job returns `409`.

- [ ] **Step 8: Update the README**

Replace the "CV profile" section's credential paragraph, since nothing needs an API key now:

````markdown
Extraction and tailoring both run on a **local Ollama model** — no API key, no cost, and
your CV never leaves the machine.

```bash
# one-time
~/.local/ollama/bin/ollama serve &
~/.local/ollama/bin/ollama pull qwen2.5:7b-instruct
```

With Ollama stopped the application still starts and job discovery works normally; CV
upload and tailoring return `503`.
````

Add after it:

````markdown
## Tailored applications

Open a job and choose **Tailor for this job**. A local model reads the ad and your CV, then
returns *only* bullet numbers and requirement phrases quoted from the ad. Everything you
would send an employer is assembled from sentences you wrote.

That is a structural guarantee, not a filter: the model cannot express a claim about you,
because the only thing it can say about you is an integer pointing at one of your own
bullets. Three guards enforce it — unknown bullet numbers and phrases absent from the ad
reject the whole result; over-broad matches are flagged for you to check.

Requirements with **no** matching bullet are highlighted. That gap is the most useful output
on the page: it is the honest distance between you and the job.

Generation takes one to two minutes on CPU.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/jobs/{id}/tailor` | Generate the application package |
| `GET` | `/api/jobs/{id}/application` | The application for a job |
| `GET` | `/api/applications` | The queue |
| `PUT` | `/api/applications/{id}/letter` | Save your prose |
| `POST` | `/api/applications/{id}/approve` | Mark reviewed |
| `DELETE` | `/api/applications/{id}` | Discard |

### What it deliberately does not do

It does not write your cover letter. A 7B model on this hardware produced unpublishable
Swedish and invented support experience the candidate never had, so prose generation was
cut. It also does not translate, since translating is generating.
````

Update "Not in this slice":

```markdown
## Not in this slice

PDF rendering of the tailored application (Slice 2c); Playwright application submission
(Slice 3); metrics and settings (Slice 4).
```

- [ ] **Step 9: Commit**

```bash
git add README.md
git commit -m "docs: document extractive tailoring"
```

---

## Done criteria

1. `./mvnw test` passes with no network calls.
2. The application starts and Slices 1 and 2a work with Ollama stopped.
3. Tailoring with no `READY` profile returns `409`.
4. Every persisted requirement is a substring of that job's own description.
5. Every persisted evidence bullet is byte-identical to a profile bullet.
6. A model response citing a bullet number outside 1..N is rejected, not persisted.
7. A response quoting a phrase absent from the ad is rejected after one retry, and the
   application is stored `GENERATION_FAILED`.
8. Requirements with no evidence are visible and amber in the UI.
9. Re-tailoring an `APPROVED` application returns `409` rather than overwriting it.
10. Nothing in the project requires an API key.
