# Job Discovery Spine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real Swedish IT job postings land in PostgreSQL, deduplicated across three sources, and render in a browser.

**Architecture:** A Spring Boot backend exposes two ingestion ports — `JobSource` (can enumerate) and `JobEnricher` (fetch-by-URL only). Three `JobSource` adapters (JobTech, Teamtailor, Varbi) feed a merge service that resolves cross-source duplicates by content fingerprint into a single `job_posting` with many `job_posting_source` rows. JobTech ingestion doubles as a discovery mechanism: apply-URL hosts are classified by ATS vendor and upserted into a self-expanding `ats_tenant` registry that the Teamtailor and Varbi adapters then poll.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 16, Flyway, Spring Data JPA, WireMock, Testcontainers, Vite + React + TypeScript + Tailwind CSS.

**Spec:** `docs/superpowers/specs/2026-08-27-job-discovery-spine-design.md`

## Global Constraints

- **Java version:** 21 (`<java.version>21</java.version>`).
- **Spring Boot version:** exactly `4.1.1`. **Never** write `4.1.1.RELEASE` — that string appears in Spring Initializr metadata but no such artifact exists in Maven Central and the build fails with "Non-resolvable parent POM".
- **Boot 4 starter names** (renamed from Boot 3 — using Boot 3 names fails):
  - web starter is `spring-boot-starter-webmvc`, **not** `spring-boot-starter-web`
  - Flyway is `spring-boot-starter-flyway`, **not** bare `flyway-core`
- **Maven is not installed.** Always build with `./mvnw` from `backend/`. Export `JAVA_HOME=/usr/lib/jvm/default` first.
- **Backend module lives in `backend/`; frontend in `frontend/`.** Root is a polyglot repo.
- **Base package:** `se.caiowain.jobseeker`.
- **Single-user, no authentication** in this slice.
- **No Spring AI, no Playwright, no PDFBox** in this slice.
- **All network access in tests goes through WireMock.** No test may hit a live job board.
- **Test fixtures are committed files** under `backend/src/test/resources/fixtures/`, captured once. Tests must be deterministic.
- **TDD:** write the failing test, watch it fail, implement minimally, watch it pass, commit.
- **JobTech `offset` hard-caps at 2000.** Requests beyond that return HTTP 400.
- Every task ends with a commit.

---

## File Structure

**Backend** (`backend/src/main/java/se/caiowain/jobseeker/`):

| Path | Responsibility |
|---|---|
| `JobseekerApplication.java` | Spring Boot entry point |
| `domain/JobPosting.java` | Canonical job entity |
| `domain/JobPostingSource.java` | Per-source sighting of a job |
| `domain/AtsTenant.java` | Discovered ATS tenant + poll state |
| `domain/SearchCriteria.java` | Persisted search definition |
| `domain/IngestRun.java` | One run, per source |
| `domain/SourceId.java` | Enum: JOBTECH, TEAMTAILOR, VARBI |
| `domain/AtsVendor.java` | Enum of ATS vendors |
| `domain/JobStatus.java` | Enum: DISCOVERED (more in later slices) |
| `domain/RunStatus.java` | Enum: RUNNING, COMPLETED, FAILED |
| `repo/*Repository.java` | Spring Data repositories |
| `ingest/RawJob.java` | Source-agnostic job record |
| `ingest/JobSource.java` | Port: enumerable source |
| `ingest/JobEnricher.java` | Port: fetch-by-URL only |
| `ingest/SearchCriteriaSpec.java` | Runtime criteria + local matching |
| `ingest/JobIdentity.java` | URL canonicalization + fingerprinting |
| `ingest/JobMergeService.java` | Dedup and merge policy |
| `ingest/AtsVendorClassifier.java` | apply-URL host → vendor + feed URL |
| `ingest/TenantRegistryService.java` | Upsert discovered tenants |
| `ingest/IngestOrchestrator.java` | Runs sources, isolates failures |
| `ingest/source/JobTechSource.java` | JobTech adapter |
| `ingest/source/TeamtailorSource.java` | Teamtailor JSON Feed adapter |
| `ingest/source/VarbiSource.java` | Varbi RSS adapter |
| `ingest/http/FeedClient.java` | Conditional GET, rate limit, backoff |
| `api/JobController.java` | `/api/jobs` |
| `api/IngestController.java` | `/api/ingest/*` |
| `api/CriteriaController.java` | `/api/criteria` |
| `api/StatsController.java` | `/api/stats` |
| `api/dto/*.java` | Response DTOs |

**Frontend** (`frontend/src/`): `api/client.ts`, `types.ts`, `pages/JobList.tsx`, `pages/JobDetail.tsx`, `components/StatsHeader.tsx`, `components/JobFilters.tsx`, `App.tsx`.

---

## Task 1: Runnable skeleton with PostgreSQL

**Files:**
- Create: `backend/` (entire Initializr scaffold), `backend/src/main/resources/application.yml`, `compose.yaml`
- Delete: `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/se/caiowain/jobseeker/JobseekerApplicationTests.java`

**Interfaces:**
- Consumes: nothing
- Produces: a booting Spring context backed by PostgreSQL via Testcontainers; `./mvnw test` green.

- [ ] **Step 1: Generate the backend scaffold**

```bash
cd /home/cai/Projects/AgenticJobSeeker
curl -s -o /tmp/jobseeker.zip \
  "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=4.1.1&javaVersion=21&groupId=se.caiowain&artifactId=jobseeker&name=jobseeker&packageName=se.caiowain.jobseeker&dependencies=web,data-jpa,postgresql,flyway,validation,actuator,docker-compose,testcontainers"
mkdir -p backend && cd backend && python3 -c "import zipfile;zipfile.ZipFile('/tmp/jobseeker.zip').extractall('.')"
chmod +x mvnw
rm -f HELP.md .gitignore .gitattributes
```

Verify the parent version is `4.1.1` and NOT `4.1.1.RELEASE`:

```bash
grep -A1 spring-boot-starter-parent pom.xml | grep '<version>'
```

- [ ] **Step 2: Move compose.yaml to repo root and name the database**

The scaffold writes `backend/compose.yaml` with a random port and generic names. Replace it at the repo root:

```bash
rm -f backend/compose.yaml
cat > /home/cai/Projects/AgenticJobSeeker/compose.yaml <<'EOF'
services:
  postgres:
    image: 'postgres:16-alpine'
    container_name: jobseeker-postgres
    environment:
      POSTGRES_DB: job_db
      POSTGRES_USER: jobseeker
      POSTGRES_PASSWORD: jobseeker
    ports:
      - '5432:5432'
    volumes:
      - jobseeker-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U jobseeker -d job_db']
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  jobseeker-pgdata:
EOF
```

Because `compose.yaml` no longer sits beside the backend, Boot's docker-compose
auto-start is switched off in the next step (`spring.docker.compose.enabled: false`)
so that tests never try to manage the container themselves.

- [ ] **Step 3: Write application.yml**

```bash
rm -f backend/src/main/resources/application.properties
cat > backend/src/main/resources/application.yml <<'EOF'
spring:
  application:
    name: jobseeker
  docker:
    compose:
      enabled: false
  datasource:
    url: jdbc:postgresql://localhost:5432/job_db
    username: jobseeker
    password: jobseeker
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info

jobseeker:
  ingest:
    enabled: true
    schedule-cron: "0 0 */6 * * *"
    user-agent: "AgenticJobSeeker/0.1 (+personal job search; contact caiowain@gmail.com)"
    request-timeout-seconds: 20
    per-host-delay-millis: 500
    max-tenant-failures: 5
EOF
```

- [ ] **Step 4: Write the failing context test**

Replace `backend/src/test/java/se/caiowain/jobseeker/JobseekerApplicationTests.java`:

```java
package se.caiowain.jobseeker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobseekerApplicationTests extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAgainstPostgres() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertThat(version).contains("PostgreSQL");
    }
}
```

Create the shared Testcontainers base class at `backend/src/test/java/se/caiowain/jobseeker/AbstractIntegrationTest.java`:

```java
package se.caiowain.jobseeker;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("job_db")
                    .withUsername("jobseeker")
                    .withPassword("jobseeker");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

Delete the Initializr extras that are not used:

```bash
rm -f backend/src/test/java/se/caiowain/jobseeker/TestJobseekerApplication.java \
      backend/src/test/java/se/caiowain/jobseeker/TestcontainersConfiguration.java
```

- [ ] **Step 5: Add the AssertJ/Testcontainers test dependencies**

Confirm `backend/pom.xml` contains these inside `<dependencies>`; add any that are missing:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 6: Run the test — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: FAIL. Flyway finds no migrations and `ddl-auto: validate` has no schema, or the migration folder is missing. This is resolved in Task 2. If it fails only because `db/migration` does not exist, create the empty directory and re-run:

```bash
mkdir -p backend/src/main/resources/db/migration
```

Expected after that: PASS (Flyway tolerates an empty migration set).

- [ ] **Step 7: Run the test — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: PASS, 1 test.

- [ ] **Step 8: Commit**

```bash
cd /home/cai/Projects/AgenticJobSeeker
git add backend compose.yaml
git commit -m "feat: Spring Boot 4.1.1 skeleton with PostgreSQL and Testcontainers"
```

---

## Task 2: Database schema via Flyway

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__initial_schema.sql`
- Test: `backend/src/test/java/se/caiowain/jobseeker/SchemaMigrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` from Task 1
- Produces: tables `job_posting`, `job_posting_source`, `ats_tenant`, `search_criteria`, `ingest_run` with the exact column names used by every later task.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/SchemaMigrationTest.java`:

```java
package se.caiowain.jobseeker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAllSpineTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "job_posting", "job_posting_source", "ats_tenant",
                "search_criteria", "ingest_run");
    }

    @Test
    void fingerprintIsUnique() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_posting' and indexdef like '%UNIQUE%fingerprint%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void sourceAdIdIsUniquePerSource() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_posting_source' and indexdef like '%UNIQUE%source%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=SchemaMigrationTest
```

Expected: FAIL — assertion error, tables absent.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V1__initial_schema.sql`:

```sql
CREATE TABLE job_posting (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    fingerprint         VARCHAR(64)  NOT NULL,
    canonical_url       VARCHAR(1024) NOT NULL,
    title               VARCHAR(512) NOT NULL,
    employer_name       VARCHAR(512),
    employer_org_number VARCHAR(32),
    municipality        VARCHAR(128),
    description         TEXT,
    language            VARCHAR(8),
    ats_vendor          VARCHAR(32)  NOT NULL DEFAULT 'OTHER',
    apply_url           VARCHAR(2048),
    published_at        TIMESTAMPTZ,
    deadline_at         TIMESTAMPTZ,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DISCOVERED',
    first_seen_at       TIMESTAMPTZ  NOT NULL,
    last_seen_at        TIMESTAMPTZ  NOT NULL
);

CREATE TABLE job_posting_source (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_posting_id BIGINT       NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    source         VARCHAR(32)  NOT NULL,
    source_ad_id   VARCHAR(256) NOT NULL,
    source_url     VARCHAR(2048),
    raw_payload    JSONB,
    fetched_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE ats_tenant (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    vendor           VARCHAR(32)  NOT NULL,
    host             VARCHAR(255) NOT NULL,
    feed_url         VARCHAR(1024) NOT NULL,
    discovered_from  VARCHAR(32)  NOT NULL,
    last_polled_at   TIMESTAMPTZ,
    etag             VARCHAR(255),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    failure_count    INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE search_criteria (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name                    VARCHAR(128) NOT NULL,
    query                   VARCHAR(512),
    municipality_codes      TEXT,
    municipality_names      TEXT,
    occupation_field_codes  TEXT,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE ingest_run (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    source       VARCHAR(32)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ,
    fetched      INTEGER      NOT NULL DEFAULT 0,
    created      INTEGER      NOT NULL DEFAULT 0,
    merged       INTEGER      NOT NULL DEFAULT 0,
    errors       INTEGER      NOT NULL DEFAULT 0,
    status       VARCHAR(32)  NOT NULL,
    message      TEXT
);

CREATE UNIQUE INDEX ux_job_posting_fingerprint       ON job_posting (fingerprint);
CREATE        INDEX ix_job_posting_canonical_url     ON job_posting (canonical_url);
CREATE        INDEX ix_job_posting_published_at      ON job_posting (published_at DESC);
CREATE        INDEX ix_job_posting_municipality      ON job_posting (municipality);
CREATE UNIQUE INDEX ux_job_posting_source_natural    ON job_posting_source (source, source_ad_id);
CREATE        INDEX ix_job_posting_source_posting    ON job_posting_source (job_posting_id);
CREATE UNIQUE INDEX ux_ats_tenant_host               ON ats_tenant (host);
CREATE        INDEX ix_ingest_run_started_at         ON ingest_run (started_at DESC);
```

Sanity-check that all five tables declare an identity primary key:

```bash
grep -c 'GENERATED BY DEFAULT' backend/src/main/resources/db/migration/V1__initial_schema.sql
```

Expected output: `5`.

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=SchemaMigrationTest
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration backend/src/test/java/se/caiowain/jobseeker/SchemaMigrationTest.java
git commit -m "feat: add V1 spine schema with three-layer identity indexes"
```

---

## Task 3: JPA entities and repositories

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/domain/{JobPosting,JobPostingSource,AtsTenant,SearchCriteria,IngestRun,SourceId,AtsVendor,JobStatus,RunStatus}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/repo/{JobPostingRepository,JobPostingSourceRepository,AtsTenantRepository,SearchCriteriaRepository,IngestRunRepository}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/domain/JobPostingPersistenceTest.java`

**Interfaces:**
- Consumes: schema from Task 2
- Produces:
  - `SourceId` enum values `JOBTECH`, `TEAMTAILOR`, `VARBI`
  - `AtsVendor` enum values `TEAMTAILOR`, `VARBI`, `REACHMEE`, `RECMAN`, `TALENTECH`, `SMARTRECRUITERS`, `WORKDAY`, `ASHBY`, `LEVER`, `GREENHOUSE`, `JOBYLON`, `OTHER`
  - `JobPostingRepository.findByFingerprint(String) : Optional<JobPosting>`
  - `JobPostingSourceRepository.findBySourceAndSourceAdId(SourceId, String) : Optional<JobPostingSource>`
  - `AtsTenantRepository.findByHost(String) : Optional<AtsTenant>`, `findByVendorAndActiveTrue(AtsVendor) : List<AtsTenant>`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/domain/JobPostingPersistenceTest.java`:

```java
package se.caiowain.jobseeker.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobPostingPersistenceTest extends AbstractIntegrationTest {

    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;

    @Test
    void persistsPostingWithTwoSourceRows() {
        JobPosting job = new JobPosting();
        job.setFingerprint("fp-test-1");
        job.setCanonicalUrl("https://example.com/jobs/1");
        job.setTitle("Java Developer");
        job.setEmployerName("Acme AB");
        job.setAtsVendor(AtsVendor.TEAMTAILOR);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        postings.save(job);

        JobPostingSource a = new JobPostingSource();
        a.setJobPosting(job);
        a.setSource(SourceId.JOBTECH);
        a.setSourceAdId("31404250");
        a.setFetchedAt(Instant.now());
        sources.save(a);

        JobPostingSource b = new JobPostingSource();
        b.setJobPosting(job);
        b.setSource(SourceId.TEAMTAILOR);
        b.setSourceAdId("49f687e1");
        b.setFetchedAt(Instant.now());
        sources.save(b);

        assertThat(postings.findByFingerprint("fp-test-1")).isPresent();
        assertThat(sources.findBySourceAndSourceAdId(SourceId.JOBTECH, "31404250")).isPresent();
        assertThat(sources.findBySourceAndSourceAdId(SourceId.TEAMTAILOR, "49f687e1")).isPresent();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobPostingPersistenceTest
```

Expected: compilation failure — the domain classes do not exist.

- [ ] **Step 3: Write the enums**

```java
// domain/SourceId.java
package se.caiowain.jobseeker.domain;
public enum SourceId { JOBTECH, TEAMTAILOR, VARBI }
```

```java
// domain/AtsVendor.java
package se.caiowain.jobseeker.domain;
public enum AtsVendor {
    TEAMTAILOR, VARBI, REACHMEE, RECMAN, TALENTECH, SMARTRECRUITERS,
    WORKDAY, ASHBY, LEVER, GREENHOUSE, JOBYLON, OTHER
}
```

```java
// domain/JobStatus.java
package se.caiowain.jobseeker.domain;
public enum JobStatus { DISCOVERED }
```

```java
// domain/RunStatus.java
package se.caiowain.jobseeker.domain;
public enum RunStatus { RUNNING, COMPLETED, FAILED }
```

- [ ] **Step 4: Write `JobPosting`**

```java
package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "job_posting")
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String fingerprint;

    @Column(name = "canonical_url", nullable = false, length = 1024)
    private String canonicalUrl;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "employer_name", length = 512)
    private String employerName;

    @Column(name = "employer_org_number", length = 32)
    private String employerOrgNumber;

    @Column(length = 128)
    private String municipality;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 8)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_vendor", nullable = false, length = 32)
    private AtsVendor atsVendor = AtsVendor.OTHER;

    @Column(name = "apply_url", length = 2048)
    private String applyUrl;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status = JobStatus.DISCOVERED;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEmployerName() { return employerName; }
    public void setEmployerName(String employerName) { this.employerName = employerName; }
    public String getEmployerOrgNumber() { return employerOrgNumber; }
    public void setEmployerOrgNumber(String v) { this.employerOrgNumber = v; }
    public String getMunicipality() { return municipality; }
    public void setMunicipality(String municipality) { this.municipality = municipality; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public AtsVendor getAtsVendor() { return atsVendor; }
    public void setAtsVendor(AtsVendor atsVendor) { this.atsVendor = atsVendor; }
    public String getApplyUrl() { return applyUrl; }
    public void setApplyUrl(String applyUrl) { this.applyUrl = applyUrl; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
```

- [ ] **Step 5: Write `JobPostingSource`**

```java
package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "job_posting_source")
public class JobPostingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SourceId source;

    @Column(name = "source_ad_id", nullable = false, length = 256)
    private String sourceAdId;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public SourceId getSource() { return source; }
    public void setSource(SourceId source) { this.source = source; }
    public String getSourceAdId() { return sourceAdId; }
    public void setSourceAdId(String sourceAdId) { this.sourceAdId = sourceAdId; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
```

- [ ] **Step 6: Write `AtsTenant`, `SearchCriteria`, `IngestRun`**

```java
package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ats_tenant")
public class AtsTenant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AtsVendor vendor;

    @Column(nullable = false, unique = true, length = 255)
    private String host;

    @Column(name = "feed_url", nullable = false, length = 1024)
    private String feedUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "discovered_from", nullable = false, length = 32)
    private SourceId discoveredFrom;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(length = 255)
    private String etag;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    public Long getId() { return id; }
    public AtsVendor getVendor() { return vendor; }
    public void setVendor(AtsVendor vendor) { this.vendor = vendor; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getFeedUrl() { return feedUrl; }
    public void setFeedUrl(String feedUrl) { this.feedUrl = feedUrl; }
    public SourceId getDiscoveredFrom() { return discoveredFrom; }
    public void setDiscoveredFrom(SourceId v) { this.discoveredFrom = v; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public void setLastPolledAt(Instant v) { this.lastPolledAt = v; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
}
```

```java
package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "search_criteria")
public class SearchCriteria {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String query;

    /** Comma-separated JobTech municipality taxonomy codes. */
    @Column(name = "municipality_codes", columnDefinition = "text")
    private String municipalityCodes;

    /** Comma-separated plain municipality names, for client-side filtering of feeds. */
    @Column(name = "municipality_names", columnDefinition = "text")
    private String municipalityNames;

    @Column(name = "occupation_field_codes", columnDefinition = "text")
    private String occupationFieldCodes;

    @Column(nullable = false)
    private boolean enabled = true;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getMunicipalityCodes() { return municipalityCodes; }
    public void setMunicipalityCodes(String v) { this.municipalityCodes = v; }
    public String getMunicipalityNames() { return municipalityNames; }
    public void setMunicipalityNames(String v) { this.municipalityNames = v; }
    public String getOccupationFieldCodes() { return occupationFieldCodes; }
    public void setOccupationFieldCodes(String v) { this.occupationFieldCodes = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

```java
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
```

- [ ] **Step 7: Write the repositories**

```java
// repo/JobPostingRepository.java
package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import se.caiowain.jobseeker.domain.JobPosting;
import java.util.Optional;

public interface JobPostingRepository
        extends JpaRepository<JobPosting, Long>, JpaSpecificationExecutor<JobPosting> {
    Optional<JobPosting> findByFingerprint(String fingerprint);
    Optional<JobPosting> findByCanonicalUrl(String canonicalUrl);
}
```

```java
// repo/JobPostingSourceRepository.java
package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.JobPostingSource;
import se.caiowain.jobseeker.domain.SourceId;
import java.util.List;
import java.util.Optional;

public interface JobPostingSourceRepository extends JpaRepository<JobPostingSource, Long> {
    Optional<JobPostingSource> findBySourceAndSourceAdId(SourceId source, String sourceAdId);
    List<JobPostingSource> findByJobPostingId(Long jobPostingId);
    long countBySource(SourceId source);
}
```

```java
// repo/AtsTenantRepository.java
package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import java.util.List;
import java.util.Optional;

public interface AtsTenantRepository extends JpaRepository<AtsTenant, Long> {
    Optional<AtsTenant> findByHost(String host);
    List<AtsTenant> findByVendorAndActiveTrue(AtsVendor vendor);
}
```

```java
// repo/SearchCriteriaRepository.java
package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.SearchCriteria;
import java.util.List;

public interface SearchCriteriaRepository extends JpaRepository<SearchCriteria, Long> {
    List<SearchCriteria> findByEnabledTrue();
}
```

```java
// repo/IngestRunRepository.java
package se.caiowain.jobseeker.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.IngestRun;

public interface IngestRunRepository extends JpaRepository<IngestRun, Long> {
    Page<IngestRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
```

- [ ] **Step 8: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobPostingPersistenceTest
```

Expected: PASS, 1 test. Hibernate's `ddl-auto: validate` also proves the entities match the Flyway schema exactly.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/domain backend/src/main/java/se/caiowain/jobseeker/repo backend/src/test
git commit -m "feat: add JPA entities and repositories for the spine schema"
```

---

## Task 4: Job identity — URL canonicalization and fingerprinting

Pure functions, no Spring, no database. This is the layer that makes cross-source dedup work, and the spec rejects the brief's URL-hash approach here.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/JobIdentity.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/JobIdentityTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `JobIdentity.canonicalUrl(String url) : String`
  - `JobIdentity.normalizeTitle(String title) : String`
  - `JobIdentity.fingerprint(String employerOrgNumber, String title, String description) : String` — returns 64-char lowercase hex SHA-256

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/JobIdentityTest.java`:

```java
package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobIdentityTest {

    @Test
    void stripsTrackingParamsFromApplyUrl() {
        // Real case: the same Avaron ad is served with an Arbetsformedlingen promotion tag.
        String tagged = "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new?promotion=2165239-arbetsformedlingen";
        String clean = "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new";
        assertThat(JobIdentity.canonicalUrl(tagged)).isEqualTo(JobIdentity.canonicalUrl(clean));
    }

    @Test
    void normalizesHostCasingWwwAndTrailingSlash() {
        assertThat(JobIdentity.canonicalUrl("https://WWW.Sverigedev.se/jobb/123/"))
                .isEqualTo(JobIdentity.canonicalUrl("https://sverigedev.se/jobb/123"));
    }

    @Test
    void stripsUtmAndSessionParamsButKeepsMeaningfulOnes() {
        assertThat(JobIdentity.canonicalUrl("https://x.se/j?utm_source=a&id=7&fbclid=z"))
                .isEqualTo("https://x.se/j?id=7");
    }

    @Test
    void handlesMalformedUrlWithoutThrowing() {
        assertThat(JobIdentity.canonicalUrl("not a url")).isEqualTo("not a url");
        assertThat(JobIdentity.canonicalUrl(null)).isNull();
    }

    @Test
    void normalizeTitleIsCaseAndPunctuationInsensitive() {
        assertThat(JobIdentity.normalizeTitle("Senior Fullstackutvecklare  Java/Angular"))
                .isEqualTo(JobIdentity.normalizeTitle("senior fullstackutvecklare java angular"));
    }

    @Test
    void fingerprintIsStableAcrossSourcesForTheSameJob() {
        // JobTech plain text vs Teamtailor HTML-stripped text for the same ad,
        // differing only in trailing whitespace and casing of the title.
        String a = JobIdentity.fingerprint("5591754279", "Senior Fullstackutvecklare Java/Angular",
                "Om foretaget Hos Avaron far du tryggheten i en fast anstallning");
        String b = JobIdentity.fingerprint("5591754279", "senior fullstackutvecklare java/angular",
                "Om foretaget Hos Avaron far du tryggheten i en fast anstallning   ");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprintDiffersForDifferentJobs() {
        String a = JobIdentity.fingerprint("5591754279", "Java Developer", "Build services");
        String b = JobIdentity.fingerprint("5591754279", "Python Developer", "Build services");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void fingerprintIsSha256Hex() {
        assertThat(JobIdentity.fingerprint("1", "t", "d")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprintToleratesNullOrgNumber() {
        assertThat(JobIdentity.fingerprint(null, "Java Developer", "Build services"))
                .hasSize(64);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobIdentityTest
```

Expected: compilation failure — `JobIdentity` does not exist.

- [ ] **Step 3: Implement `JobIdentity`**

```java
package se.caiowain.jobseeker.ingest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Layer 2 and layer 3 of the three-layer identity scheme from the spec.
 *
 * <p>A plain hash of the job URL is deliberately NOT used: the same ad is served under
 * different URLs by JobTech, by aggregators, and by the employer's own ATS domain.
 */
public final class JobIdentity {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "promotion", "utm_source", "utm_medium", "utm_campaign", "utm_term",
            "utm_content", "fbclid", "gclid", "msclkid", "ref", "source",
            "sessionid", "jsessionid");

    private JobIdentity() {
    }

    /** Normalizes a URL so the same job under cosmetic URL variations compares equal. */
    public static String canonicalUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return url;
            }
            String host = uri.getHost().toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = cleanQuery(uri.getQuery());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
            return scheme + "://" + host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String cleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> kept = Arrays.stream(query.split("&"))
                .filter(p -> !p.isBlank())
                .filter(p -> !TRACKING_PARAMS.contains(
                        p.contains("=") ? p.substring(0, p.indexOf('=')).toLowerCase()
                                        : p.toLowerCase()))
                .sorted()
                .collect(Collectors.toList());
        return String.join("&", kept);
    }

    /** Lowercases, strips punctuation, collapses whitespace. */
    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.toLowerCase()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Layer 3: content fingerprint. This is what recognizes the same job arriving
     * from JobTech and from the employer's own Teamtailor feed.
     */
    public static String fingerprint(String employerOrgNumber, String title, String description) {
        String normalizedDescription = description == null ? "" : description;
        normalizedDescription = normalizedDescription
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        if (normalizedDescription.length() > 512) {
            normalizedDescription = normalizedDescription.substring(0, 512);
        }
        String payload = (employerOrgNumber == null ? "" : employerOrgNumber.trim())
                + "|" + normalizeTitle(title)
                + "|" + normalizedDescription;
        return sha256Hex(payload);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobIdentityTest
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest/JobIdentity.java backend/src/test/java/se/caiowain/jobseeker/ingest/JobIdentityTest.java
git commit -m "feat: add URL canonicalization and content fingerprinting"
```

---

## Task 5: Ingestion ports and the source-agnostic job record

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/{RawJob,JobSource,JobEnricher,SearchCriteriaSpec}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/SearchCriteriaSpecTest.java`

**Interfaces:**
- Consumes: `SourceId` from Task 3
- Produces:
  - `RawJob` record with components `sourceAdId, sourceUrl, title, employerName, employerOrgNumber, municipality, description, language, applyUrl, publishedAt, deadlineAt, rawPayload` — every adapter returns this type
  - `JobSource.id() : SourceId` and `JobSource.fetch(List<SearchCriteriaSpec>) : List<RawJob>`
  - `JobEnricher.supports(String applyUrl) : boolean` and `JobEnricher.enrich(String applyUrl) : Optional<RawJob>`
  - `SearchCriteriaSpec.matchesLocally(RawJob) : boolean`
  - `SearchCriteriaSpec.from(SearchCriteria) : SearchCriteriaSpec`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/SearchCriteriaSpecTest.java`:

```java
package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCriteriaSpecTest {

    private RawJob job(String title, String description, String municipality) {
        return new RawJob("id", "https://x.se/1", title, "Acme AB", "5591754279",
                municipality, description, "sv", "https://x.se/apply", null, null, "{}");
    }

    @Test
    void matchesWhenAnyKeywordAppearsInTitle() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "java", "java utvecklare", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Senior Java Developer", "irrelevant", "Stockholm"))).isTrue();
    }

    @Test
    void matchesWhenKeywordAppearsOnlyInDescription() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "java", "java", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Systemutvecklare", "Vi soker en Java-utvecklare", "Stockholm"))).isTrue();
    }

    @Test
    void doesNotMatchWhenNoKeywordPresent() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "java", "java", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Sjukskoterska", "Vard och omsorg", "Stockholm"))).isFalse();
    }

    @Test
    void municipalityFilterAppliesWhenPresent() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "sthlm", "java", List.of(), List.of("Stockholm"), List.of());
        assertThat(spec.matchesLocally(job("Java Developer", "d", "Stockholm"))).isTrue();
        assertThat(spec.matchesLocally(job("Java Developer", "d", "Malmo"))).isFalse();
    }

    @Test
    void emptyQueryMatchesEverything() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "all", "", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Anything", "at all", "Kiruna"))).isTrue();
    }

    @Test
    void matchingIsDiacriticAndCaseInsensitive() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "sys", "systemutvecklare", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("SYSTEMUTVECKLARE till Stockholm", "d", "Stockholm"))).isTrue();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=SearchCriteriaSpecTest
```

Expected: compilation failure — the types do not exist.

- [ ] **Step 3: Write `RawJob`**

```java
package se.caiowain.jobseeker.ingest;

import java.time.Instant;

/**
 * Source-agnostic representation of one job as fetched. Every {@link JobSource}
 * adapter maps its own payload shape into this record.
 *
 * @param rawPayload the original payload as JSON, retained so later slices can
 *                   re-derive fields without re-crawling the source
 */
public record RawJob(
        String sourceAdId,
        String sourceUrl,
        String title,
        String employerName,
        String employerOrgNumber,
        String municipality,
        String description,
        String language,
        String applyUrl,
        Instant publishedAt,
        Instant deadlineAt,
        String rawPayload
) {
}
```

- [ ] **Step 4: Write the two ports**

```java
package se.caiowain.jobseeker.ingest;

import se.caiowain.jobseeker.domain.SourceId;
import java.util.List;

/** A source that can enumerate jobs on its own. */
public interface JobSource {

    SourceId id();

    /**
     * Fetches jobs matching any of the given criteria. Implementations that support
     * server-side filtering (JobTech) push the criteria into the request; implementations
     * reading whole-catalogue feeds (Teamtailor, Varbi) fetch everything and filter with
     * {@link SearchCriteriaSpec#matchesLocally(RawJob)}.
     */
    List<RawJob> fetch(List<SearchCriteriaSpec> criteria);
}
```

```java
package se.caiowain.jobseeker.ingest;

import java.util.Optional;

/**
 * A source that cannot enumerate jobs, only fetch one whose URL is already known.
 *
 * <p>ReachMee is the motivating case: it serves from shared hosts behind per-tenant
 * signed {@code validator} URLs, so its catalogue cannot be listed. Implementations
 * are deferred to a later slice; the port exists so they have a home.
 */
public interface JobEnricher {

    boolean supports(String applyUrl);

    Optional<RawJob> enrich(String applyUrl);
}
```

- [ ] **Step 5: Write `SearchCriteriaSpec`**

```java
package se.caiowain.jobseeker.ingest;

import se.caiowain.jobseeker.domain.SearchCriteria;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runtime view of a persisted {@link SearchCriteria}, plus the local matching used by
 * feed-based sources that offer no server-side query.
 */
public record SearchCriteriaSpec(
        String name,
        String query,
        List<String> municipalityCodes,
        List<String> municipalityNames,
        List<String> occupationFieldCodes
) {

    public static SearchCriteriaSpec from(SearchCriteria entity) {
        return new SearchCriteriaSpec(
                entity.getName(),
                entity.getQuery() == null ? "" : entity.getQuery(),
                split(entity.getMunicipalityCodes()),
                split(entity.getMunicipalityNames()),
                split(entity.getOccupationFieldCodes()));
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** Client-side filter for whole-catalogue feeds. */
    public boolean matchesLocally(RawJob job) {
        if (!municipalityNames.isEmpty()) {
            String jobMunicipality = fold(job.municipality());
            boolean municipalityMatch = municipalityNames.stream()
                    .anyMatch(m -> fold(m).equals(jobMunicipality));
            if (!municipalityMatch) {
                return false;
            }
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String haystack = fold(job.title()) + " " + fold(job.description());
        return Arrays.stream(query.split("\\s+"))
                .map(SearchCriteriaSpec::fold)
                .filter(k -> !k.isEmpty())
                .anyMatch(haystack::contains);
    }

    /** Lowercase, strip diacritics, so "Malmo" matches "Malmö". */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalized.toLowerCase();
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=SearchCriteriaSpecTest
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest backend/src/test/java/se/caiowain/jobseeker/ingest
git commit -m "feat: add ingestion ports, RawJob record and criteria matching"
```

---

## Task 6: Merge service — the dedup policy

The highest-value logic in the slice. A duplicate is never discarded: it becomes a second `job_posting_source` row and may upgrade the canonical posting.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/JobMergeService.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/MergeOutcome.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/JobMergeServiceTest.java`

**Interfaces:**
- Consumes: `JobIdentity` (Task 4), `RawJob` (Task 5), repositories (Task 3)
- Produces: `JobMergeService.ingest(SourceId source, RawJob raw, AtsVendor vendor) : MergeOutcome`, where `MergeOutcome` is an enum of `CREATED`, `MERGED`, `UNCHANGED`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/JobMergeServiceTest.java`:

```java
package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobMergeServiceTest extends AbstractIntegrationTest {

    @Autowired JobMergeService merge;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;

    private static final String ORG = "5591754279";
    private static final String TITLE = "Senior Fullstackutvecklare Java/Angular";
    private static final String DESC =
            "Om foretaget Hos Avaron far du tryggheten i en fast anstallning kombinerat med variationen";

    @BeforeEach
    void clean() {
        sources.deleteAll();
        postings.deleteAll();
    }

    private RawJob jobTechAd() {
        return new RawJob("31404250",
                "https://arbetsformedlingen.se/platsbanken/annonser/31404250",
                TITLE, "Avaron AB", ORG, "Norrkoping", DESC, "sv",
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new?promotion=2165239-arbetsformedlingen",
                Instant.parse("2026-08-27T09:14:18Z"), null, "{\"src\":\"jobtech\"}");
    }

    private RawJob teamtailorAd() {
        return new RawJob("49f687e1-ceb1-4701-a5a0-14baf0f77d1d",
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular",
                TITLE, "Avaron AB", ORG, "Norrkoping",
                DESC + " av att arbeta ute hos olika kunder. Vi tillsatter specialister inom allt fran teknik.",
                "sv",
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new",
                Instant.parse("2026-08-26T14:56:37Z"), null, "{\"src\":\"teamtailor\"}");
    }

    @Test
    void firstSightingCreatesPosting() {
        assertThat(merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR))
                .isEqualTo(MergeOutcome.CREATED);
        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void sameSourceTwiceIsUnchanged() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        assertThat(merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR))
                .isEqualTo(MergeOutcome.UNCHANGED);
        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void crossSourceDuplicateMergesIntoOnePostingWithTwoSources() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        assertThat(merge.ingest(SourceId.TEAMTAILOR, teamtailorAd(), AtsVendor.TEAMTAILOR))
                .isEqualTo(MergeOutcome.MERGED);

        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(2);

        JobPosting job = postings.findAll().getFirst();
        assertThat(sources.findByJobPostingId(job.getId())).hasSize(2);
    }

    @Test
    void mergeKeepsTheRicherDescription() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        merge.ingest(SourceId.TEAMTAILOR, teamtailorAd(), AtsVendor.TEAMTAILOR);

        JobPosting job = postings.findAll().getFirst();
        assertThat(job.getDescription()).isEqualTo(teamtailorAd().description());
        assertThat(job.getDescription().length()).isGreaterThan(DESC.length());
    }

    @Test
    void mergeStripsTrackingFromApplyUrl() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        JobPosting job = postings.findAll().getFirst();
        assertThat(job.getApplyUrl()).doesNotContain("promotion=");
    }

    @Test
    void mergeRefreshesLastSeenButKeepsFirstSeen() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        Instant firstSeen = postings.findAll().getFirst().getFirstSeenAt();

        merge.ingest(SourceId.TEAMTAILOR, teamtailorAd(), AtsVendor.TEAMTAILOR);
        JobPosting job = postings.findAll().getFirst();

        assertThat(job.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(job.getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void differentJobsDoNotMerge() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        RawJob other = new RawJob("999", "https://x.se/999", "Sjukskoterska", "Vard AB",
                "1111111111", "Malmo", "Helt annat jobb", "sv", "https://x.se/apply", null, null, "{}");
        assertThat(merge.ingest(SourceId.JOBTECH, other, AtsVendor.OTHER))
                .isEqualTo(MergeOutcome.CREATED);
        assertThat(postings.count()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobMergeServiceTest
```

Expected: compilation failure — `JobMergeService` and `MergeOutcome` do not exist.

- [ ] **Step 3: Write `MergeOutcome`**

```java
package se.caiowain.jobseeker.ingest;

public enum MergeOutcome {
    /** A new canonical posting was created. */
    CREATED,
    /** An existing posting gained an additional source row. */
    MERGED,
    /** This exact (source, adId) pair was already stored; nothing changed. */
    UNCHANGED
}
```

- [ ] **Step 4: Write `JobMergeService`**

```java
package se.caiowain.jobseeker.ingest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Implements the spec's merge policy: a duplicate is never discarded. It becomes an
 * additional {@link JobPostingSource} row and may upgrade the canonical posting's
 * description and apply URL.
 */
@Service
public class JobMergeService {

    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;

    public JobMergeService(JobPostingRepository postings, JobPostingSourceRepository sources) {
        this.postings = postings;
        this.sources = sources;
    }

    @Transactional
    public MergeOutcome ingest(SourceId source, RawJob raw, AtsVendor vendor) {
        // Layer 1: natural key. Already seen from this exact source.
        Optional<JobPostingSource> existingSource =
                sources.findBySourceAndSourceAdId(source, raw.sourceAdId());
        if (existingSource.isPresent()) {
            JobPosting posting = existingSource.get().getJobPosting();
            posting.setLastSeenAt(Instant.now());
            postings.save(posting);
            return MergeOutcome.UNCHANGED;
        }

        String fingerprint = JobIdentity.fingerprint(
                raw.employerOrgNumber(), raw.title(), raw.description());

        // Layer 3: content fingerprint, then layer 2 as a fallback.
        Optional<JobPosting> existing = postings.findByFingerprint(fingerprint);
        if (existing.isEmpty()) {
            String canonical = JobIdentity.canonicalUrl(raw.sourceUrl());
            if (canonical != null) {
                existing = postings.findByCanonicalUrl(canonical);
            }
        }

        if (existing.isPresent()) {
            JobPosting posting = existing.get();
            upgrade(posting, raw, vendor);
            postings.save(posting);
            attachSource(posting, source, raw);
            return MergeOutcome.MERGED;
        }

        JobPosting posting = create(raw, vendor, fingerprint);
        postings.save(posting);
        attachSource(posting, source, raw);
        return MergeOutcome.CREATED;
    }

    private JobPosting create(RawJob raw, AtsVendor vendor, String fingerprint) {
        Instant now = Instant.now();
        JobPosting posting = new JobPosting();
        posting.setFingerprint(fingerprint);
        posting.setCanonicalUrl(JobIdentity.canonicalUrl(raw.sourceUrl()));
        posting.setTitle(raw.title());
        posting.setEmployerName(raw.employerName());
        posting.setEmployerOrgNumber(raw.employerOrgNumber());
        posting.setMunicipality(raw.municipality());
        posting.setDescription(raw.description());
        posting.setLanguage(raw.language());
        posting.setAtsVendor(vendor == null ? AtsVendor.OTHER : vendor);
        posting.setApplyUrl(JobIdentity.canonicalUrl(raw.applyUrl()));
        posting.setPublishedAt(raw.publishedAt());
        posting.setDeadlineAt(raw.deadlineAt());
        posting.setStatus(JobStatus.DISCOVERED);
        posting.setFirstSeenAt(now);
        posting.setLastSeenAt(now);
        return posting;
    }

    /** Richest description wins; a non-null apply URL and vendor fill gaps. */
    private void upgrade(JobPosting posting, RawJob raw, AtsVendor vendor) {
        String incoming = raw.description();
        String current = posting.getDescription();
        if (incoming != null && (current == null || incoming.length() > current.length())) {
            posting.setDescription(incoming);
        }
        if (raw.applyUrl() != null && !raw.applyUrl().isBlank()) {
            posting.setApplyUrl(JobIdentity.canonicalUrl(raw.applyUrl()));
        }
        if (posting.getEmployerOrgNumber() == null && raw.employerOrgNumber() != null) {
            posting.setEmployerOrgNumber(raw.employerOrgNumber());
        }
        if (posting.getMunicipality() == null && raw.municipality() != null) {
            posting.setMunicipality(raw.municipality());
        }
        if (vendor != null && vendor != AtsVendor.OTHER) {
            posting.setAtsVendor(vendor);
        }
        posting.setLastSeenAt(Instant.now());
    }

    private void attachSource(JobPosting posting, SourceId source, RawJob raw) {
        JobPostingSource row = new JobPostingSource();
        row.setJobPosting(posting);
        row.setSource(source);
        row.setSourceAdId(raw.sourceAdId());
        row.setSourceUrl(raw.sourceUrl());
        row.setRawPayload(raw.rawPayload());
        row.setFetchedAt(Instant.now());
        sources.save(row);
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobMergeServiceTest
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest backend/src/test/java/se/caiowain/jobseeker/ingest
git commit -m "feat: add cross-source merge service with richest-description policy"
```

---

## Task 7: ATS vendor classifier and self-expanding tenant registry

This is what removes the need to hand-maintain tenant lists: JobTech's apply URLs seed Teamtailor and Varbi discovery.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/AtsVendorClassifier.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/TenantRegistryService.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/AtsVendorClassifierTest.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/TenantRegistryServiceTest.java`

**Interfaces:**
- Consumes: `AtsVendor`, `AtsTenantRepository`, `SourceId`
- Produces:
  - `AtsVendorClassifier.classify(String applyUrl, String reference) : AtsVendor`
  - `AtsVendorClassifier.feedUrlFor(AtsVendor vendor, String host) : Optional<String>`
  - `AtsVendorClassifier.hostOf(String url) : Optional<String>`
  - `TenantRegistryService.registerFromApplyUrl(String applyUrl, String reference, SourceId discoveredFrom) : Optional<AtsTenant>`
  - `TenantRegistryService.recordSuccess(AtsTenant, String etag)` and `recordFailure(AtsTenant)`

- [ ] **Step 1: Write the failing classifier test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/AtsVendorClassifierTest.java`:

```java
package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.AtsVendor;

import static org.assertj.core.api.Assertions.assertThat;

class AtsVendorClassifierTest {

    private final AtsVendorClassifier classifier = new AtsVendorClassifier();

    @Test
    void detectsTeamtailorFromReference() {
        assertThat(classifier.classify("https://jobs.example.se/jobs/1", "teamtailor-8161354-abc"))
                .isEqualTo(AtsVendor.TEAMTAILOR);
    }

    @Test
    void detectsTeamtailorFromCustomDomainUrlShape() {
        // Real case: Teamtailor tenants on custom domains use /jobs/<id>-<slug>/applications/new
        assertThat(classifier.classify(
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new",
                null))
                .isEqualTo(AtsVendor.TEAMTAILOR);
    }

    @Test
    void detectsVarbiFromHost() {
        assertThat(classifier.classify(
                "https://transportstyrelsen.varbi.com/se/what:job/jobID:962618/type:job/where:125/apply:1", null))
                .isEqualTo(AtsVendor.VARBI);
    }

    @Test
    void detectsReachmeeFromHost() {
        assertThat(classifier.classify("https://web103.reachmee.com/ext/I003/584/main?site=19", null))
                .isEqualTo(AtsVendor.REACHMEE);
    }

    @Test
    void fallsBackToOtherForUnknownDomains() {
        assertThat(classifier.classify("https://careers.randomcompany.se/apply", null))
                .isEqualTo(AtsVendor.OTHER);
    }

    @Test
    void handlesNullApplyUrl() {
        assertThat(classifier.classify(null, null)).isEqualTo(AtsVendor.OTHER);
    }

    @Test
    void buildsTeamtailorFeedUrl() {
        assertThat(classifier.feedUrlFor(AtsVendor.TEAMTAILOR, "jobs.avaron.se"))
                .contains("https://jobs.avaron.se/jobs.json");
    }

    @Test
    void buildsVarbiFeedUrl() {
        assertThat(classifier.feedUrlFor(AtsVendor.VARBI, "transportstyrelsen.varbi.com"))
                .contains("https://transportstyrelsen.varbi.com/what:rssfeed/");
    }

    @Test
    void noFeedUrlForNonEnumerableVendors() {
        assertThat(classifier.feedUrlFor(AtsVendor.REACHMEE, "web103.reachmee.com")).isEmpty();
        assertThat(classifier.feedUrlFor(AtsVendor.OTHER, "x.se")).isEmpty();
    }

    @Test
    void extractsHostAndDropsWww() {
        assertThat(classifier.hostOf("https://WWW.Example.SE/path")).contains("example.se");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=AtsVendorClassifierTest
```

Expected: compilation failure — `AtsVendorClassifier` does not exist.

- [ ] **Step 3: Implement `AtsVendorClassifier`**

```java
package se.caiowain.jobseeker.ingest;

import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.domain.AtsVendor;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps a job's apply URL onto the ATS vendor behind it, and — for vendors that publish
 * an enumerable feed — onto that feed's URL.
 *
 * <p>Vendor shares measured across 2,000 Swedish IT ads: Teamtailor 29.6%, Varbi 6.0%,
 * ReachMee 5.5%, Recman 4.7%. Only Teamtailor and Varbi expose public feeds.
 */
@Component
public class AtsVendorClassifier {

    /** Host substrings that identify a vendor outright. */
    private static final Map<String, AtsVendor> HOST_MARKERS = new LinkedHashMap<>();

    static {
        HOST_MARKERS.put("teamtailor.com", AtsVendor.TEAMTAILOR);
        HOST_MARKERS.put("varbi.com", AtsVendor.VARBI);
        HOST_MARKERS.put("reachmee.com", AtsVendor.REACHMEE);
        HOST_MARKERS.put("recman.", AtsVendor.RECMAN);
        HOST_MARKERS.put("talentech.io", AtsVendor.TALENTECH);
        HOST_MARKERS.put("smartrecruiters.com", AtsVendor.SMARTRECRUITERS);
        HOST_MARKERS.put("myworkdayjobs.com", AtsVendor.WORKDAY);
        HOST_MARKERS.put("ashbyhq.com", AtsVendor.ASHBY);
        HOST_MARKERS.put("lever.co", AtsVendor.LEVER);
        HOST_MARKERS.put("greenhouse.io", AtsVendor.GREENHOUSE);
        HOST_MARKERS.put("jobylon.com", AtsVendor.JOBYLON);
    }

    /** Teamtailor's signature path shape, used by tenants on custom domains. */
    private static final Pattern TEAMTAILOR_PATH =
            Pattern.compile("/jobs/\\d+-[^/]+(/applications/new)?/?$");

    public AtsVendor classify(String applyUrl, String reference) {
        String blob = ((applyUrl == null ? "" : applyUrl) + " "
                + (reference == null ? "" : reference)).toLowerCase(Locale.ROOT);

        for (Map.Entry<String, AtsVendor> entry : HOST_MARKERS.entrySet()) {
            if (blob.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (blob.contains("teamtailor")) {
            return AtsVendor.TEAMTAILOR;
        }
        if (applyUrl != null) {
            try {
                URI uri = URI.create(applyUrl);
                if (uri.getPath() != null && TEAMTAILOR_PATH.matcher(uri.getPath()).find()) {
                    return AtsVendor.TEAMTAILOR;
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to OTHER
            }
        }
        return AtsVendor.OTHER;
    }

    /** Only vendors with an enumerable public feed return a value. */
    public Optional<String> feedUrlFor(AtsVendor vendor, String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        return switch (vendor) {
            case TEAMTAILOR -> Optional.of("https://" + host + "/jobs.json");
            case VARBI -> Optional.of("https://" + host + "/what:rssfeed/");
            default -> Optional.empty();
        };
    }

    public Optional<String> hostOf(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return Optional.empty();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return Optional.of(host.startsWith("www.") ? host.substring(4) : host);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run classifier test — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=AtsVendorClassifierTest
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Write the failing registry test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/TenantRegistryServiceTest.java`:

```java
package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TenantRegistryServiceTest extends AbstractIntegrationTest {

    @Autowired TenantRegistryService registry;
    @Autowired AtsTenantRepository tenants;

    @BeforeEach
    void clean() {
        tenants.deleteAll();
    }

    @Test
    void registersTeamtailorTenantFromApplyUrl() {
        var tenant = registry.registerFromApplyUrl(
                "https://jobs.avaron.se/jobs/8278099-x/applications/new?promotion=1-arbetsformedlingen",
                null, SourceId.JOBTECH);

        assertThat(tenant).isPresent();
        assertThat(tenant.get().getVendor()).isEqualTo(AtsVendor.TEAMTAILOR);
        assertThat(tenant.get().getHost()).isEqualTo("jobs.avaron.se");
        assertThat(tenant.get().getFeedUrl()).isEqualTo("https://jobs.avaron.se/jobs.json");
    }

    @Test
    void registersVarbiTenantFromApplyUrl() {
        var tenant = registry.registerFromApplyUrl(
                "https://transportstyrelsen.varbi.com/se/what:job/jobID:962618/", null, SourceId.JOBTECH);

        assertThat(tenant).isPresent();
        assertThat(tenant.get().getVendor()).isEqualTo(AtsVendor.VARBI);
        assertThat(tenant.get().getFeedUrl()).isEqualTo("https://transportstyrelsen.varbi.com/what:rssfeed/");
    }

    @Test
    void doesNotRegisterVendorsWithoutFeeds() {
        assertThat(registry.registerFromApplyUrl(
                "https://web103.reachmee.com/ext/I003/584/main?site=19", null, SourceId.JOBTECH))
                .isEmpty();
        assertThat(tenants.count()).isZero();
    }

    @Test
    void registrationIsIdempotentPerHost() {
        registry.registerFromApplyUrl("https://jobs.avaron.se/jobs/1-a/applications/new", null, SourceId.JOBTECH);
        registry.registerFromApplyUrl("https://jobs.avaron.se/jobs/2-b/applications/new", null, SourceId.JOBTECH);
        assertThat(tenants.count()).isEqualTo(1);
    }

    @Test
    void deactivatesTenantAfterRepeatedFailures() {
        AtsTenant tenant = registry.registerFromApplyUrl(
                "https://jobs.avaron.se/jobs/1-a/applications/new", null, SourceId.JOBTECH).orElseThrow();

        for (int i = 0; i < 5; i++) {
            registry.recordFailure(tenant);
        }

        assertThat(tenants.findByHost("jobs.avaron.se").orElseThrow().isActive()).isFalse();
        assertThat(tenants.findByVendorAndActiveTrue(AtsVendor.TEAMTAILOR)).isEmpty();
    }

    @Test
    void successResetsFailureCountAndStoresEtag() {
        AtsTenant tenant = registry.registerFromApplyUrl(
                "https://jobs.avaron.se/jobs/1-a/applications/new", null, SourceId.JOBTECH).orElseThrow();
        registry.recordFailure(tenant);
        registry.recordSuccess(tenant, "\"abc123\"");

        AtsTenant reloaded = tenants.findByHost("jobs.avaron.se").orElseThrow();
        assertThat(reloaded.getFailureCount()).isZero();
        assertThat(reloaded.getEtag()).isEqualTo("\"abc123\"");
        assertThat(reloaded.getLastPolledAt()).isNotNull();
    }
}
```

- [ ] **Step 6: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TenantRegistryServiceTest
```

Expected: compilation failure — `TenantRegistryService` does not exist.

- [ ] **Step 7: Implement `TenantRegistryService`**

```java
package se.caiowain.jobseeker.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * The self-expanding tenant registry. JobTech ingestion feeds apply URLs in here;
 * every host that resolves to a vendor with a public feed becomes a pollable tenant.
 * No tenant list is ever configured by hand.
 */
@Service
public class TenantRegistryService {

    private final AtsTenantRepository tenants;
    private final AtsVendorClassifier classifier;
    private final int maxFailures;

    public TenantRegistryService(AtsTenantRepository tenants,
                                 AtsVendorClassifier classifier,
                                 @Value("${jobseeker.ingest.max-tenant-failures:5}") int maxFailures) {
        this.tenants = tenants;
        this.classifier = classifier;
        this.maxFailures = maxFailures;
    }

    @Transactional
    public Optional<AtsTenant> registerFromApplyUrl(String applyUrl, String reference, SourceId discoveredFrom) {
        AtsVendor vendor = classifier.classify(applyUrl, reference);
        Optional<String> host = classifier.hostOf(applyUrl);
        if (host.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> feedUrl = classifier.feedUrlFor(vendor, host.get());
        if (feedUrl.isEmpty()) {
            return Optional.empty();
        }

        Optional<AtsTenant> existing = tenants.findByHost(host.get());
        if (existing.isPresent()) {
            return existing;
        }

        AtsTenant tenant = new AtsTenant();
        tenant.setVendor(vendor);
        tenant.setHost(host.get());
        tenant.setFeedUrl(feedUrl.get());
        tenant.setDiscoveredFrom(discoveredFrom);
        tenant.setActive(true);
        return Optional.of(tenants.save(tenant));
    }

    @Transactional
    public void recordSuccess(AtsTenant tenant, String etag) {
        AtsTenant managed = tenants.findById(tenant.getId()).orElse(tenant);
        managed.setFailureCount(0);
        managed.setLastPolledAt(Instant.now());
        if (etag != null && !etag.isBlank()) {
            managed.setEtag(etag);
        }
        tenants.save(managed);
    }

    /** A tenant that never had a feed is normal, not exceptional: deactivate, do not retry forever. */
    @Transactional
    public void recordFailure(AtsTenant tenant) {
        AtsTenant managed = tenants.findById(tenant.getId()).orElse(tenant);
        managed.setFailureCount(managed.getFailureCount() + 1);
        managed.setLastPolledAt(Instant.now());
        if (managed.getFailureCount() >= maxFailures) {
            managed.setActive(false);
        }
        tenants.save(managed);
    }
}
```

- [ ] **Step 8: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TenantRegistryServiceTest
```

Expected: PASS, 6 tests.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest backend/src/test/java/se/caiowain/jobseeker/ingest
git commit -m "feat: classify ATS vendors and self-expand the tenant registry"
```

---

## Task 8: Feed HTTP client with conditional GET and throttling

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/http/FeedClient.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/http/FeedResponse.java`
- Modify: `backend/pom.xml` (add WireMock test dependency)
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/http/FeedClientTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `FeedResponse` record: `int status, String body, String etag, boolean notModified`
  - `FeedClient.get(String url, String etag) : FeedResponse`
  - `FeedClient.get(String url) : FeedResponse`

- [ ] **Step 1: Add WireMock to `backend/pom.xml`**

Inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.13.2</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/http/FeedClientTest.java`:

```java
package se.caiowain.jobseeker.ingest.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class FeedClientTest {

    private WireMockServer server;
    private FeedClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = new FeedClient("TestAgent/1.0", 10, 0);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    @Test
    void fetchesBodyAndEtag() {
        server.stubFor(get(urlEqualTo("/jobs.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"v1\"")
                        .withBody("{\"items\":[]}")));

        FeedResponse response = client.get(url("/jobs.json"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"items\":[]}");
        assertThat(response.etag()).isEqualTo("\"v1\"");
        assertThat(response.notModified()).isFalse();
    }

    @Test
    void sendsIfNoneMatchAndReportsNotModified() {
        server.stubFor(get(urlEqualTo("/jobs.json"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        FeedResponse response = client.get(url("/jobs.json"), "\"v1\"");

        assertThat(response.notModified()).isTrue();
        assertThat(response.status()).isEqualTo(304);
    }

    @Test
    void sendsConfiguredUserAgent() {
        server.stubFor(get(urlEqualTo("/jobs.json"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        client.get(url("/jobs.json"));

        server.verify(getRequestedFor(urlEqualTo("/jobs.json"))
                .withHeader("User-Agent", equalTo("TestAgent/1.0")));
    }

    @Test
    void returnsStatusForNotFoundWithoutThrowing() {
        server.stubFor(get(urlEqualTo("/missing.json"))
                .willReturn(aResponse().withStatus(404).withBody("nope")));

        FeedResponse response = client.get(url("/missing.json"));

        assertThat(response.status()).isEqualTo(404);
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=FeedClientTest
```

Expected: compilation failure — `FeedClient` and `FeedResponse` do not exist.

- [ ] **Step 4: Write `FeedResponse`**

```java
package se.caiowain.jobseeker.ingest.http;

public record FeedResponse(int status, String body, String etag, boolean notModified) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
```

- [ ] **Step 5: Write `FeedClient`**

```java
package se.caiowain.jobseeker.ingest.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared HTTP access for feed polling. Adds a contactable User-Agent, conditional GET
 * via ETag, and a per-host delay so that polling hundreds of tenant feeds stays polite.
 */
@Component
public class FeedClient {

    private static final Logger log = LoggerFactory.getLogger(FeedClient.class);

    private final HttpClient http;
    private final String userAgent;
    private final Duration timeout;
    private final long perHostDelayMillis;
    private final Map<String, Long> lastRequestByHost = new ConcurrentHashMap<>();

    public FeedClient(@Value("${jobseeker.ingest.user-agent}") String userAgent,
                      @Value("${jobseeker.ingest.request-timeout-seconds:20}") int timeoutSeconds,
                      @Value("${jobseeker.ingest.per-host-delay-millis:500}") long perHostDelayMillis) {
        this.userAgent = userAgent;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.perHostDelayMillis = perHostDelayMillis;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public FeedResponse get(String url) {
        return get(url, null);
    }

    public FeedResponse get(String url, String etag) {
        URI uri = URI.create(url);
        throttle(uri.getHost());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/feed+json, application/json, application/rss+xml, */*");
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }

        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseEtag = response.headers().firstValue("ETag").orElse(null);
            boolean notModified = response.statusCode() == 304;
            return new FeedResponse(response.statusCode(),
                    notModified ? null : response.body(), responseEtag, notModified);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedFetchException("Interrupted fetching " + url, e);
        } catch (Exception e) {
            throw new FeedFetchException("Failed fetching " + url + ": " + e.getMessage(), e);
        }
    }

    /** Keeps at least the configured gap between two requests to the same host. */
    private void throttle(String host) {
        if (host == null || perHostDelayMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastRequestByHost.get(host);
        if (previous != null) {
            long wait = perHostDelayMillis - (now - previous);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastRequestByHost.put(host, System.currentTimeMillis());
    }

    public static class FeedFetchException extends RuntimeException {
        public FeedFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=FeedClientTest
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/main/java/se/caiowain/jobseeker/ingest/http backend/src/test/java/se/caiowain/jobseeker/ingest/http
git commit -m "feat: add feed HTTP client with conditional GET and per-host throttling"
```

---

## Task 9: JobTech source adapter

JobTech is the only source with server-side filtering, and it doubles as the seed for tenant discovery.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/source/JobTechSource.java`
- Create: `backend/src/test/resources/fixtures/jobtech-search.json`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/source/JobTechSourceTest.java`
- Modify: `backend/src/main/resources/application.yml` (add `jobseeker.sources.jobtech.base-url`)

**Interfaces:**
- Consumes: `FeedClient` (Task 8), `RawJob`/`JobSource`/`SearchCriteriaSpec` (Task 5), `TenantRegistryService` (Task 7)
- Produces: `JobTechSource implements JobSource`, `id()` returns `SourceId.JOBTECH`

- [ ] **Step 1: Add the base URL to `application.yml`**

Append under the existing `jobseeker:` block, as a sibling of `ingest:`:

```yaml
  sources:
    jobtech:
      base-url: https://jobsearch.api.jobtechdev.se
      page-size: 100
      max-offset: 2000
```

- [ ] **Step 2: Create the fixture**

`backend/src/test/resources/fixtures/jobtech-search.json` — a two-hit response shaped exactly like the live API:

```json
{
  "total": { "value": 2 },
  "positions": 2,
  "hits": [
    {
      "id": "31404250",
      "headline": "Senior Fullstackutvecklare Java/Angular",
      "description": {
        "text": "Om foretaget Hos Avaron far du tryggheten i en fast anstallning kombinerat med variationen av att arbeta ute hos olika kunder."
      },
      "employer": {
        "name": "Avaron AB",
        "organization_number": "5591754279"
      },
      "workplace_address": {
        "municipality": "Norrkoping",
        "municipality_code": "0581"
      },
      "application_details": {
        "url": "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new?promotion=2165239-arbetsformedlingen",
        "reference": ""
      },
      "webpage_url": "https://arbetsformedlingen.se/platsbanken/annonser/31404250",
      "publication_date": "2026-08-27T11:14:18+02:00",
      "application_deadline": "2026-09-27T23:59:59+02:00"
    },
    {
      "id": "962618",
      "headline": "Portfolj- och modellansvarig ITSM",
      "description": {
        "text": "Vi far samhallet att fungera for manniskor pa vag, pa spar, i luften och pa sjon."
      },
      "employer": {
        "name": "Transportstyrelsen",
        "organization_number": "2021006038"
      },
      "workplace_address": {
        "municipality": "Norrkoping",
        "municipality_code": "0581"
      },
      "application_details": {
        "url": "https://transportstyrelsen.varbi.com/se/what:job/jobID:962618/type:job/where:125/apply:1",
        "reference": ""
      },
      "webpage_url": "https://arbetsformedlingen.se/platsbanken/annonser/962618",
      "publication_date": "2026-08-27T09:00:00+02:00",
      "application_deadline": null
    }
  ]
}
```

- [ ] **Step 3: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/source/JobTechSourceTest.java`:

```java
package se.caiowain.jobseeker.ingest.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.RawJob;
import se.caiowain.jobseeker.ingest.SearchCriteriaSpec;
import se.caiowain.jobseeker.ingest.TenantRegistryService;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobTechSourceTest extends AbstractIntegrationTest {

    @Autowired FeedClient feedClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRegistryService registry;
    @Autowired AtsTenantRepository tenants;

    private WireMockServer server;
    private JobTechSource source;

    @BeforeEach
    void setUp() throws Exception {
        tenants.deleteAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String body = new String(getClass().getResourceAsStream("/fixtures/jobtech-search.json")
                .readAllBytes(), StandardCharsets.UTF_8);

        server.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        source = new JobTechSource(feedClient, objectMapper, registry,
                "http://localhost:" + server.port(), 100, 2000);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void reportsItsSourceId() {
        assertThat(source.id()).isEqualTo(SourceId.JOBTECH);
    }

    @Test
    void mapsHitsIntoRawJobs() {
        List<RawJob> jobs = source.fetch(List.of(
                new SearchCriteriaSpec("java", "java", List.of(), List.of(), List.of())));

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.getFirst();
        assertThat(first.sourceAdId()).isEqualTo("31404250");
        assertThat(first.title()).isEqualTo("Senior Fullstackutvecklare Java/Angular");
        assertThat(first.employerName()).isEqualTo("Avaron AB");
        assertThat(first.employerOrgNumber()).isEqualTo("5591754279");
        assertThat(first.municipality()).isEqualTo("Norrkoping");
        assertThat(first.sourceUrl()).isEqualTo("https://arbetsformedlingen.se/platsbanken/annonser/31404250");
        assertThat(first.applyUrl()).contains("jobs.avaron.se");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.rawPayload()).contains("31404250");
    }

    @Test
    void sendsCriteriaAsQueryParameters() {
        source.fetch(List.of(new SearchCriteriaSpec(
                "sthlm", "java utvecklare", List.of("AvNB_uwa_6n6"), List.of(), List.of("apaJ_2ja_LuF"))));

        server.verify(getRequestedFor(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("java utvecklare"))
                .withQueryParam("municipality", equalTo("AvNB_uwa_6n6"))
                .withQueryParam("occupation-field", equalTo("apaJ_2ja_LuF")));
    }

    @Test
    void seedsTenantRegistryFromApplyUrls() {
        source.fetch(List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of())));

        assertThat(tenants.findByHost("jobs.avaron.se")).isPresent();
        assertThat(tenants.findByHost("jobs.avaron.se").orElseThrow().getVendor())
                .isEqualTo(AtsVendor.TEAMTAILOR);
        assertThat(tenants.findByHost("transportstyrelsen.varbi.com")).isPresent();
        assertThat(tenants.findByHost("transportstyrelsen.varbi.com").orElseThrow().getVendor())
                .isEqualTo(AtsVendor.VARBI);
    }

    @Test
    void neverRequestsBeyondTheOffsetCeiling() {
        // Fixture always returns 2 hits, so paging stops immediately; assert the guard
        // by confirming no request carried an offset above the configured maximum.
        source.fetch(List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of())));

        server.getAllServeEvents().forEach(event -> {
            String offset = event.getRequest().queryParameter("offset").firstValue();
            assertThat(Integer.parseInt(offset)).isLessThanOrEqualTo(2000);
        });
    }
}
```

- [ ] **Step 4: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobTechSourceTest
```

Expected: compilation failure — `JobTechSource` does not exist.

- [ ] **Step 5: Implement `JobTechSource`**

```java
package se.caiowain.jobseeker.ingest.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.*;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.ingest.http.FeedResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arbetsformedlingen's JobTech Dev API. Keyless, open government data, and the only
 * source in this slice that filters server-side.
 *
 * <p>Its apply URLs are also the seed for {@link TenantRegistryService}: every ad tells
 * us which ATS the employer uses, which is how Teamtailor and Varbi tenants get found.
 */
@Component
public class JobTechSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(JobTechSource.class);

    private final FeedClient http;
    private final ObjectMapper mapper;
    private final TenantRegistryService registry;
    private final String baseUrl;
    private final int pageSize;
    private final int maxOffset;

    public JobTechSource(FeedClient http,
                         ObjectMapper mapper,
                         TenantRegistryService registry,
                         @Value("${jobseeker.sources.jobtech.base-url}") String baseUrl,
                         @Value("${jobseeker.sources.jobtech.page-size:100}") int pageSize,
                         @Value("${jobseeker.sources.jobtech.max-offset:2000}") int maxOffset) {
        this.http = http;
        this.mapper = mapper;
        this.registry = registry;
        this.baseUrl = baseUrl;
        this.pageSize = pageSize;
        this.maxOffset = maxOffset;
    }

    @Override
    public SourceId id() {
        return SourceId.JOBTECH;
    }

    @Override
    public List<RawJob> fetch(List<SearchCriteriaSpec> criteria) {
        List<RawJob> all = new ArrayList<>();
        for (SearchCriteriaSpec spec : criteria) {
            all.addAll(fetchOne(spec));
        }
        return all;
    }

    private List<RawJob> fetchOne(SearchCriteriaSpec spec) {
        List<RawJob> jobs = new ArrayList<>();
        int offset = 0;

        while (offset <= maxOffset) {
            FeedResponse response = http.get(buildUrl(spec, offset));
            if (!response.isSuccess()) {
                log.warn("JobTech returned {} for criteria '{}' at offset {}",
                        response.status(), spec.name(), offset);
                break;
            }

            JsonNode root = readTree(response.body());
            JsonNode hits = root.path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                break;
            }

            for (JsonNode hit : hits) {
                jobs.add(toRawJob(hit));
            }

            int total = root.path("total").path("value").asInt(0);
            offset += pageSize;
            if (offset >= total) {
                break;
            }
            if (offset > maxOffset) {
                log.warn("Criteria '{}' matches {} ads, exceeding the JobTech offset ceiling of {}. "
                        + "Results truncated; narrow the criteria.", spec.name(), total, maxOffset);
                break;
            }
        }
        return jobs;
    }

    private String buildUrl(SearchCriteriaSpec spec, int offset) {
        StringBuilder url = new StringBuilder(baseUrl).append("/search?limit=")
                .append(pageSize).append("&offset=").append(offset);
        if (spec.query() != null && !spec.query().isBlank()) {
            url.append("&q=").append(URLEncoder.encode(spec.query(), StandardCharsets.UTF_8));
        }
        for (String code : spec.municipalityCodes()) {
            url.append("&municipality=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        }
        for (String code : spec.occupationFieldCodes()) {
            url.append("&occupation-field=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private RawJob toRawJob(JsonNode hit) {
        String applyUrl = text(hit.path("application_details").path("url"));
        String reference = text(hit.path("application_details").path("reference"));

        // Seed tenant discovery: this is how Teamtailor and Varbi tenants are found.
        try {
            registry.registerFromApplyUrl(applyUrl, reference, SourceId.JOBTECH);
        } catch (RuntimeException e) {
            log.debug("Tenant registration skipped for {}: {}", applyUrl, e.getMessage());
        }

        return new RawJob(
                text(hit.path("id")),
                text(hit.path("webpage_url")),
                text(hit.path("headline")),
                text(hit.path("employer").path("name")),
                text(hit.path("employer").path("organization_number")),
                text(hit.path("workplace_address").path("municipality")),
                text(hit.path("description").path("text")),
                "sv",
                applyUrl,
                parseInstant(text(hit.path("publication_date"))),
                parseInstant(text(hit.path("application_deadline"))),
                hit.toString());
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed JobTech response", e);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobTechSourceTest
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest/source backend/src/test backend/src/main/resources/application.yml
git commit -m "feat: add JobTech source adapter that seeds tenant discovery"
```

---

## Task 10: Teamtailor source adapter

Reads the JSON Feed published by every Teamtailor career site. Whole-catalogue, so criteria are applied client-side.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/source/TeamtailorSource.java`
- Create: `backend/src/test/resources/fixtures/teamtailor-jobs.json`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/source/TeamtailorSourceTest.java`

**Interfaces:**
- Consumes: `FeedClient`, `AtsTenantRepository`, `TenantRegistryService`, `SearchCriteriaSpec`
- Produces: `TeamtailorSource implements JobSource`, `id()` returns `SourceId.TEAMTAILOR`

- [ ] **Step 1: Create the fixture**

`backend/src/test/resources/fixtures/teamtailor-jobs.json`:

```json
{
  "version": "https://jsonfeed.org/version/1",
  "title": "Avaron AB",
  "home_page_url": "https://jobs.avaron.se",
  "feed_url": "https://jobs.avaron.se/jobs.json",
  "items": [
    {
      "id": "49f687e1-ceb1-4701-a5a0-14baf0f77d1d",
      "title": "Senior Fullstackutvecklare Java/Angular",
      "url": "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular",
      "date_published": "2026-08-26T16:56:37+02:00",
      "content_html": "<h3>Om foretaget</h3><p>Hos Avaron far du tryggheten i en fast anstallning kombinerat med variationen av att arbeta ute hos olika kunder. Vi tillsatter specialister inom allt fran teknik till affarsstod.</p>",
      "_jobposting": {
        "@context": "http://schema.org/",
        "@type": "JobPosting",
        "title": "Senior Fullstackutvecklare Java/Angular",
        "hiringOrganization": { "@type": "Organization", "name": "Avaron AB" },
        "jobLocation": {
          "@type": "Place",
          "address": { "@type": "PostalAddress", "addressLocality": "Norrkoping" }
        },
        "validThrough": "2026-09-27T23:59:59+02:00"
      }
    },
    {
      "id": "aa11bb22-0000-4444-9999-ccddeeff0011",
      "title": "Sjukskoterska till bemanning",
      "url": "https://jobs.avaron.se/jobs/8279000-sjukskoterska-till-bemanning",
      "date_published": "2026-08-25T10:00:00+02:00",
      "content_html": "<p>Vi soker sjukskoterskor for uppdrag inom vard och omsorg.</p>",
      "_jobposting": {
        "@context": "http://schema.org/",
        "@type": "JobPosting",
        "title": "Sjukskoterska till bemanning",
        "hiringOrganization": { "@type": "Organization", "name": "Avaron AB" },
        "jobLocation": {
          "@type": "Place",
          "address": { "@type": "PostalAddress", "addressLocality": "Malmo" }
        }
      }
    }
  ]
}
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/source/TeamtailorSourceTest.java`:

```java
package se.caiowain.jobseeker.ingest.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.RawJob;
import se.caiowain.jobseeker.ingest.SearchCriteriaSpec;
import se.caiowain.jobseeker.ingest.TenantRegistryService;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TeamtailorSourceTest extends AbstractIntegrationTest {

    @Autowired FeedClient feedClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired AtsTenantRepository tenants;
    @Autowired TenantRegistryService registry;

    private WireMockServer server;
    private TeamtailorSource source;

    @BeforeEach
    void setUp() throws Exception {
        tenants.deleteAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String body = new String(getClass().getResourceAsStream("/fixtures/teamtailor-jobs.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        server.stubFor(get(urlPathEqualTo("/jobs.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/feed+json")
                        .withHeader("ETag", "\"tt-1\"")
                        .withBody(body)));

        AtsTenant tenant = new AtsTenant();
        tenant.setVendor(AtsVendor.TEAMTAILOR);
        tenant.setHost("localhost");
        tenant.setFeedUrl("http://localhost:" + server.port() + "/jobs.json");
        tenant.setDiscoveredFrom(SourceId.JOBTECH);
        tenant.setActive(true);
        tenants.save(tenant);

        source = new TeamtailorSource(feedClient, objectMapper, tenants, registry);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private SearchCriteriaSpec all() {
        return new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of());
    }

    @Test
    void reportsItsSourceId() {
        assertThat(source.id()).isEqualTo(SourceId.TEAMTAILOR);
    }

    @Test
    void mapsFeedItemsIntoRawJobs() {
        List<RawJob> jobs = source.fetch(List.of(all()));

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.getFirst();
        assertThat(first.sourceAdId()).isEqualTo("49f687e1-ceb1-4701-a5a0-14baf0f77d1d");
        assertThat(first.title()).isEqualTo("Senior Fullstackutvecklare Java/Angular");
        assertThat(first.employerName()).isEqualTo("Avaron AB");
        assertThat(first.municipality()).isEqualTo("Norrkoping");
        assertThat(first.applyUrl()).endsWith("/applications/new");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.deadlineAt()).isNotNull();
    }

    @Test
    void stripsHtmlFromDescription() {
        RawJob first = source.fetch(List.of(all())).getFirst();
        assertThat(first.description()).doesNotContain("<h3>", "<p>");
        assertThat(first.description()).contains("Hos Avaron far du tryggheten");
    }

    @Test
    void filtersLocallyBecauseTheFeedHasNoQueryParameter() {
        List<RawJob> jobs = source.fetch(List.of(
                new SearchCriteriaSpec("java", "java", List.of(), List.of(), List.of())));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().title()).contains("Java");
    }

    @Test
    void storesEtagForConditionalGet() {
        source.fetch(List.of(all()));
        assertThat(tenants.findByHost("localhost").orElseThrow().getEtag()).isEqualTo("\"tt-1\"");
    }

    @Test
    void skipsInactiveTenants() {
        AtsTenant tenant = tenants.findByHost("localhost").orElseThrow();
        tenant.setActive(false);
        tenants.save(tenant);

        assertThat(source.fetch(List.of(all()))).isEmpty();
    }

    @Test
    void aFailingTenantDoesNotAbortTheWholeFetch() {
        AtsTenant broken = new AtsTenant();
        broken.setVendor(AtsVendor.TEAMTAILOR);
        broken.setHost("broken.invalid");
        broken.setFeedUrl("http://broken.invalid:1/jobs.json");
        broken.setDiscoveredFrom(SourceId.JOBTECH);
        broken.setActive(true);
        tenants.save(broken);

        assertThat(source.fetch(List.of(all()))).hasSize(2);
        assertThat(tenants.findByHost("broken.invalid").orElseThrow().getFailureCount())
                .isGreaterThan(0);
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TeamtailorSourceTest
```

Expected: compilation failure — `TeamtailorSource` does not exist.

- [ ] **Step 4: Implement `TeamtailorSource`**

```java
package se.caiowain.jobseeker.ingest.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.*;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.ingest.http.FeedResponse;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the public JSON Feed that every Teamtailor career site publishes at
 * {@code /jobs.json}. Keyless, and the {@code _jobposting} extension carries
 * schema.org JobPosting data richer than the JobTech listing for the same ad.
 *
 * <p>The feed is whole-catalogue, so search criteria are applied client-side.
 */
@Component
public class TeamtailorSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(TeamtailorSource.class);

    private final FeedClient http;
    private final ObjectMapper mapper;
    private final AtsTenantRepository tenants;
    private final TenantRegistryService registry;

    public TeamtailorSource(FeedClient http, ObjectMapper mapper,
                            AtsTenantRepository tenants, TenantRegistryService registry) {
        this.http = http;
        this.mapper = mapper;
        this.tenants = tenants;
        this.registry = registry;
    }

    @Override
    public SourceId id() {
        return SourceId.TEAMTAILOR;
    }

    @Override
    public List<RawJob> fetch(List<SearchCriteriaSpec> criteria) {
        List<RawJob> results = new ArrayList<>();

        for (AtsTenant tenant : tenants.findByVendorAndActiveTrue(AtsVendor.TEAMTAILOR)) {
            try {
                FeedResponse response = http.get(tenant.getFeedUrl(), tenant.getEtag());
                if (response.notModified()) {
                    registry.recordSuccess(tenant, tenant.getEtag());
                    continue;
                }
                if (!response.isSuccess()) {
                    registry.recordFailure(tenant);
                    continue;
                }

                JsonNode root = mapper.readTree(response.body());
                for (JsonNode item : root.path("items")) {
                    RawJob job = toRawJob(item);
                    if (matchesAny(job, criteria)) {
                        results.add(job);
                    }
                }
                registry.recordSuccess(tenant, response.etag());
            } catch (Exception e) {
                // One bad tenant must never abort the run.
                log.debug("Teamtailor tenant {} failed: {}", tenant.getHost(), e.getMessage());
                registry.recordFailure(tenant);
            }
        }
        return results;
    }

    private boolean matchesAny(RawJob job, List<SearchCriteriaSpec> criteria) {
        return criteria.isEmpty() || criteria.stream().anyMatch(c -> c.matchesLocally(job));
    }

    private RawJob toRawJob(JsonNode item) {
        JsonNode posting = item.path("_jobposting");
        String url = text(item.path("url"));

        return new RawJob(
                text(item.path("id")),
                url,
                text(item.path("title")),
                text(posting.path("hiringOrganization").path("name")),
                null, // Teamtailor feeds do not expose the employer org number
                text(posting.path("jobLocation").path("address").path("addressLocality")),
                stripHtml(text(item.path("content_html"))),
                null,
                url == null ? null : url + "/applications/new",
                parseInstant(text(item.path("date_published"))),
                parseInstant(text(posting.path("validThrough"))),
                item.toString());
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=TeamtailorSourceTest
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest/source backend/src/test
git commit -m "feat: add Teamtailor JSON Feed source adapter"
```

---

## Task 11: Varbi source adapter

Varbi publishes RSS 2.0 per tenant. Parsed with the JDK's own XML parser — no extra dependency.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/source/VarbiSource.java`
- Create: `backend/src/test/resources/fixtures/varbi-feed.xml`
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/source/VarbiSourceTest.java`

**Interfaces:**
- Consumes: `FeedClient`, `AtsTenantRepository`, `TenantRegistryService`, `SearchCriteriaSpec`
- Produces: `VarbiSource implements JobSource`, `id()` returns `SourceId.VARBI`

- [ ] **Step 1: Create the fixture**

`backend/src/test/resources/fixtures/varbi-feed.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>Lediga jobb hos Transportstyrelsen</title>
    <link>https://transportstyrelsen.varbi.com/</link>
    <description>Lediga tjanster</description>
    <item>
      <title>Portfolj- och modellansvarig ITSM, Norrkoping</title>
      <link>https://transportstyrelsen.varbi.com/en/what:job/jobID:962603/</link>
      <description>Vi far samhallet att fungera for manniskor pa vag, pa spar, i luften och pa sjon. Vi soker nu en systemutvecklare med erfarenhet av Java.</description>
      <pubDate>Thu, 27 Aug 2026 00:00:00 +0200</pubDate>
      <guid>https://transportstyrelsen.varbi.com/en/what:job/jobID:962603/</guid>
    </item>
    <item>
      <title>Receptionist till huvudkontoret</title>
      <link>https://transportstyrelsen.varbi.com/en/what:job/jobID:962604/</link>
      <description>Vi soker en receptionist till vart huvudkontor i Norrkoping.</description>
      <pubDate>Wed, 26 Aug 2026 00:00:00 +0200</pubDate>
      <guid>https://transportstyrelsen.varbi.com/en/what:job/jobID:962604/</guid>
    </item>
  </channel>
</rss>
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/source/VarbiSourceTest.java`:

```java
package se.caiowain.jobseeker.ingest.source;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.RawJob;
import se.caiowain.jobseeker.ingest.SearchCriteriaSpec;
import se.caiowain.jobseeker.ingest.TenantRegistryService;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VarbiSourceTest extends AbstractIntegrationTest {

    @Autowired FeedClient feedClient;
    @Autowired AtsTenantRepository tenants;
    @Autowired TenantRegistryService registry;

    private WireMockServer server;
    private VarbiSource source;

    @BeforeEach
    void setUp() throws Exception {
        tenants.deleteAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String body = new String(getClass().getResourceAsStream("/fixtures/varbi-feed.xml")
                .readAllBytes(), StandardCharsets.UTF_8);
        server.stubFor(get(urlPathEqualTo("/what:rssfeed/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withBody(body)));

        AtsTenant tenant = new AtsTenant();
        tenant.setVendor(AtsVendor.VARBI);
        tenant.setHost("localhost");
        tenant.setFeedUrl("http://localhost:" + server.port() + "/what:rssfeed/");
        tenant.setDiscoveredFrom(SourceId.JOBTECH);
        tenant.setActive(true);
        tenants.save(tenant);

        source = new VarbiSource(feedClient, tenants, registry);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private SearchCriteriaSpec all() {
        return new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of());
    }

    @Test
    void reportsItsSourceId() {
        assertThat(source.id()).isEqualTo(SourceId.VARBI);
    }

    @Test
    void parsesRssItemsIntoRawJobs() {
        List<RawJob> jobs = source.fetch(List.of(all()));

        assertThat(jobs).hasSize(2);
        RawJob first = jobs.getFirst();
        assertThat(first.title()).isEqualTo("Portfolj- och modellansvarig ITSM, Norrkoping");
        assertThat(first.sourceUrl()).isEqualTo("https://transportstyrelsen.varbi.com/en/what:job/jobID:962603/");
        assertThat(first.description()).contains("Vi far samhallet att fungera");
        assertThat(first.publishedAt()).isNotNull();
    }

    @Test
    void derivesStableSourceAdIdFromJobId() {
        RawJob first = source.fetch(List.of(all())).getFirst();
        assertThat(first.sourceAdId()).isEqualTo("962603");
    }

    @Test
    void filtersLocally() {
        List<RawJob> jobs = source.fetch(List.of(
                new SearchCriteriaSpec("dev", "systemutvecklare", List.of(), List.of(), List.of())));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().title()).contains("ITSM");
    }

    @Test
    void aFailingTenantIsRecordedNotThrown() {
        AtsTenant broken = new AtsTenant();
        broken.setVendor(AtsVendor.VARBI);
        broken.setHost("broken.invalid");
        broken.setFeedUrl("http://broken.invalid:1/what:rssfeed/");
        broken.setDiscoveredFrom(SourceId.JOBTECH);
        broken.setActive(true);
        tenants.save(broken);

        assertThat(source.fetch(List.of(all()))).hasSize(2);
        assertThat(tenants.findByHost("broken.invalid").orElseThrow().getFailureCount())
                .isGreaterThan(0);
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=VarbiSourceTest
```

Expected: compilation failure — `VarbiSource` does not exist.

- [ ] **Step 4: Implement `VarbiSource`**

```java
package se.caiowain.jobseeker.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import se.caiowain.jobseeker.domain.AtsTenant;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.ingest.*;
import se.caiowain.jobseeker.ingest.http.FeedClient;
import se.caiowain.jobseeker.ingest.http.FeedResponse;
import se.caiowain.jobseeker.repo.AtsTenantRepository;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Varbi publishes RSS 2.0 per tenant at {@code /what:rssfeed/}. Heavily used by Swedish
 * public-sector employers. Parsed with the JDK XML parser to avoid another dependency.
 */
@Component
public class VarbiSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(VarbiSource.class);
    private static final Pattern JOB_ID = Pattern.compile("jobID:(\\d+)");

    private final FeedClient http;
    private final AtsTenantRepository tenants;
    private final TenantRegistryService registry;

    public VarbiSource(FeedClient http, AtsTenantRepository tenants, TenantRegistryService registry) {
        this.http = http;
        this.tenants = tenants;
        this.registry = registry;
    }

    @Override
    public SourceId id() {
        return SourceId.VARBI;
    }

    @Override
    public List<RawJob> fetch(List<SearchCriteriaSpec> criteria) {
        List<RawJob> results = new ArrayList<>();

        for (AtsTenant tenant : tenants.findByVendorAndActiveTrue(AtsVendor.VARBI)) {
            try {
                FeedResponse response = http.get(tenant.getFeedUrl(), tenant.getEtag());
                if (response.notModified()) {
                    registry.recordSuccess(tenant, tenant.getEtag());
                    continue;
                }
                if (!response.isSuccess()) {
                    registry.recordFailure(tenant);
                    continue;
                }

                for (RawJob job : parse(response.body())) {
                    if (criteria.isEmpty() || criteria.stream().anyMatch(c -> c.matchesLocally(job))) {
                        results.add(job);
                    }
                }
                registry.recordSuccess(tenant, response.etag());
            } catch (Exception e) {
                log.debug("Varbi tenant {} failed: {}", tenant.getHost(), e.getMessage());
                registry.recordFailure(tenant);
            }
        }
        return results;
    }

    private List<RawJob> parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden the parser: these feeds are third-party input.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        NodeList items = document.getElementsByTagName("item");
        List<RawJob> jobs = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String link = tag(item, "link");
            jobs.add(new RawJob(
                    jobIdFrom(link, tag(item, "guid")),
                    link,
                    tag(item, "title"),
                    null,
                    null,
                    null,
                    tag(item, "description"),
                    "sv",
                    link,
                    parseRfc1123(tag(item, "pubDate")),
                    null,
                    "{}"));
        }
        return jobs;
    }

    /** Varbi URLs embed a stable numeric job id: .../what:job/jobID:962603/ */
    private static String jobIdFrom(String link, String guid) {
        for (String candidate : new String[]{link, guid}) {
            if (candidate == null) {
                continue;
            }
            Matcher matcher = JOB_ID.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return guid != null ? guid : link;
    }

    private static String tag(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    private static Instant parseRfc1123(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=VarbiSourceTest
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/ingest/source backend/src/test
git commit -m "feat: add Varbi RSS source adapter"
```

---

## Task 12: Ingest orchestrator, scheduling and seed criteria

Runs every source, writes one `ingest_run` row per source, and guarantees a failing source cannot take down the run.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/IngestOrchestrator.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/ingest/IngestScheduler.java`
- Create: `backend/src/main/resources/db/migration/V2__seed_search_criteria.sql`
- Modify: `backend/src/main/java/se/caiowain/jobseeker/JobseekerApplication.java` (add `@EnableScheduling`)
- Test: `backend/src/test/java/se/caiowain/jobseeker/ingest/IngestOrchestratorTest.java`

**Interfaces:**
- Consumes: `JobSource` beans, `JobMergeService`, `AtsVendorClassifier`, `SearchCriteriaRepository`, `IngestRunRepository`
- Produces: `IngestOrchestrator.runAll() : List<IngestRun>` and `IngestOrchestrator.runOne(JobSource, List<SearchCriteriaSpec>) : IngestRun`

- [ ] **Step 1: Write the seed migration**

`backend/src/main/resources/db/migration/V2__seed_search_criteria.sql`:

```sql
INSERT INTO search_criteria (name, query, municipality_codes, municipality_names, occupation_field_codes, enabled)
VALUES
    ('Java/systemutvecklare Stockholm',
     'java systemutvecklare backend fullstack',
     'AvNB_uwa_6n6',
     'Stockholm',
     'apaJ_2ja_LuF',
     TRUE),
    ('IT-jobb hela Sverige',
     'utvecklare developer engineer',
     '',
     '',
     'apaJ_2ja_LuF',
     TRUE);
```

> `AvNB_uwa_6n6` is the JobTech taxonomy code for Stockholm municipality and
> `apaJ_2ja_LuF` is the code for the Data/IT occupation field. Both were confirmed
> against the live taxonomy API during research.

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/ingest/IngestOrchestratorTest.java`:

```java
package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IngestOrchestratorTest extends AbstractIntegrationTest {

    @Autowired IngestOrchestrator orchestrator;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;
    @Autowired IngestRunRepository runs;
    @Autowired SearchCriteriaRepository criteria;

    @BeforeEach
    void clean() {
        sources.deleteAll();
        postings.deleteAll();
        runs.deleteAll();
    }

    /** A stand-in source that returns a fixed list. */
    private JobSource stubSource(SourceId id, List<RawJob> jobs) {
        return new JobSource() {
            @Override public SourceId id() { return id; }
            @Override public List<RawJob> fetch(List<SearchCriteriaSpec> c) { return jobs; }
        };
    }

    private JobSource explodingSource(SourceId id) {
        return new JobSource() {
            @Override public SourceId id() { return id; }
            @Override public List<RawJob> fetch(List<SearchCriteriaSpec> c) {
                throw new IllegalStateException("source is down");
            }
        };
    }

    private RawJob job(String adId, String title) {
        return new RawJob(adId, "https://x.se/" + adId, title, "Acme AB", "5591754279",
                "Stockholm", "Description for " + title, "sv",
                "https://jobs.acme.se/jobs/1-a/applications/new",
                Instant.now(), null, "{}");
    }

    @Test
    void recordsOneRunPerSourceWithCounts() {
        IngestRun run = orchestrator.runOne(
                stubSource(SourceId.JOBTECH, List.of(job("1", "Java Developer"), job("2", "Python Developer"))),
                List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of())));

        assertThat(run.getSource()).isEqualTo(SourceId.JOBTECH);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getFetched()).isEqualTo(2);
        assertThat(run.getCreated()).isEqualTo(2);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(postings.count()).isEqualTo(2);
    }

    @Test
    void countsMergesSeparatelyFromCreates() {
        var spec = List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of()));
        orchestrator.runOne(stubSource(SourceId.JOBTECH, List.of(job("1", "Java Developer"))), spec);

        IngestRun second = orchestrator.runOne(
                stubSource(SourceId.TEAMTAILOR, List.of(job("tt-1", "Java Developer"))), spec);

        assertThat(second.getMerged()).isEqualTo(1);
        assertThat(second.getCreated()).isZero();
        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(2);
    }

    @Test
    void aFailingSourceIsRecordedAsFailedNotThrown() {
        IngestRun run = orchestrator.runOne(explodingSource(SourceId.VARBI), List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getMessage()).contains("source is down");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void runAllIsolatesFailuresBetweenSources() {
        List<IngestRun> results = orchestrator.runAll(List.of(
                stubSource(SourceId.JOBTECH, List.of(job("1", "Java Developer"))),
                explodingSource(SourceId.VARBI),
                stubSource(SourceId.TEAMTAILOR, List.of(job("tt-9", "Kotlin Developer")))));

        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED)).hasSize(2);
        assertThat(results.stream().filter(r -> r.getStatus() == RunStatus.FAILED)).hasSize(1);
        assertThat(postings.count()).isEqualTo(2);
    }

    @Test
    void seedCriteriaAreLoadedFromTheDatabase() {
        assertThat(criteria.findByEnabledTrue()).isNotEmpty();
        assertThat(criteria.findByEnabledTrue())
                .anyMatch(c -> c.getName().contains("Stockholm"));
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=IngestOrchestratorTest
```

Expected: compilation failure — `IngestOrchestrator` does not exist.

- [ ] **Step 4: Implement `IngestOrchestrator`**

```java
package se.caiowain.jobseeker.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.IngestRunRepository;
import se.caiowain.jobseeker.repo.SearchCriteriaRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the configured sources. Per-source isolation is the contract: one adapter
 * throwing must never prevent the others from running or from recording their results.
 */
@Service
public class IngestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrator.class);

    private final List<JobSource> sources;
    private final JobMergeService merge;
    private final AtsVendorClassifier classifier;
    private final SearchCriteriaRepository criteriaRepository;
    private final IngestRunRepository runs;

    public IngestOrchestrator(List<JobSource> sources,
                              JobMergeService merge,
                              AtsVendorClassifier classifier,
                              SearchCriteriaRepository criteriaRepository,
                              IngestRunRepository runs) {
        this.sources = sources;
        this.merge = merge;
        this.classifier = classifier;
        this.criteriaRepository = criteriaRepository;
        this.runs = runs;
    }

    /** Runs every registered source with the enabled criteria from the database. */
    public List<IngestRun> runAll() {
        return runAll(sources);
    }

    public List<IngestRun> runAll(List<JobSource> sourcesToRun) {
        List<SearchCriteriaSpec> specs = loadCriteria();
        List<IngestRun> results = new ArrayList<>();
        for (JobSource source : sourcesToRun) {
            results.add(runOne(source, specs));
        }
        return results;
    }

    public List<SearchCriteriaSpec> loadCriteria() {
        return criteriaRepository.findByEnabledTrue().stream()
                .map(SearchCriteriaSpec::from)
                .toList();
    }

    public IngestRun runOne(JobSource source, List<SearchCriteriaSpec> criteria) {
        IngestRun run = new IngestRun();
        run.setSource(source.id());
        run.setStartedAt(Instant.now());
        run.setStatus(RunStatus.RUNNING);
        runs.save(run);

        try {
            List<RawJob> fetched = source.fetch(criteria);
            run.setFetched(fetched.size());

            int created = 0;
            int merged = 0;
            int errors = 0;

            for (RawJob raw : fetched) {
                try {
                    AtsVendor vendor = classifier.classify(raw.applyUrl(), null);
                    MergeOutcome outcome = merge.ingest(source.id(), raw, vendor);
                    if (outcome == MergeOutcome.CREATED) {
                        created++;
                    } else if (outcome == MergeOutcome.MERGED) {
                        merged++;
                    }
                } catch (RuntimeException e) {
                    // A single malformed ad must not abandon the rest of the batch.
                    errors++;
                    log.warn("Failed to ingest ad {} from {}: {}",
                            raw.sourceAdId(), source.id(), e.getMessage());
                }
            }

            run.setCreated(created);
            run.setMerged(merged);
            run.setErrors(errors);
            run.setStatus(RunStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Source {} failed entirely: {}", source.id(), e.getMessage());
            run.setStatus(RunStatus.FAILED);
            run.setMessage(e.getMessage());
        } finally {
            run.setFinishedAt(Instant.now());
            runs.save(run);
        }
        return run;
    }
}
```

- [ ] **Step 5: Implement `IngestScheduler`**

```java
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
```

- [ ] **Step 6: Enable scheduling and disable it in tests**

Add `@EnableScheduling` to `JobseekerApplication`:

```java
package se.caiowain.jobseeker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobseekerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobseekerApplication.class, args);
    }
}
```

Stop the scheduler from firing during tests by adding to `AbstractIntegrationTest`'s `datasourceProperties` method:

```java
registry.add("jobseeker.ingest.enabled", () -> "false");
registry.add("jobseeker.ingest.schedule-cron", () -> "-");
// Global constraint: no test may reach a live job board. Any source bean that is
// autowired rather than hand-built in a test points at a dead local port.
registry.add("jobseeker.sources.jobtech.base-url", () -> "http://127.0.0.1:1");
```

> This is what keeps `IngestApiTest.triggersAnIngestRun` honest: it exercises the
> endpoint's contract while every real source fails fast against an unreachable host,
> which is itself the per-source isolation behaviour the run is meant to prove.

- [ ] **Step 7: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=IngestOrchestratorTest
```

Expected: PASS, 5 tests.

- [ ] **Step 8: Run the whole suite**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: PASS, all tests green.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "feat: add ingest orchestrator with per-source isolation and scheduling"
```

---

## Task 13: REST API for jobs

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/api/JobController.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/api/dto/{JobSummaryDto,JobDetailDto,JobSourceDto,PageDto}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/api/JobQueryService.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/api/JobControllerTest.java`

**Interfaces:**
- Consumes: `JobPostingRepository` (with `JpaSpecificationExecutor`), `JobPostingSourceRepository`
- Produces: `GET /api/jobs`, `GET /api/jobs/{id}`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/api/JobControllerTest.java`:

```java
package se.caiowain.jobseeker.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class JobControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;

    private Long javaJobId;

    @BeforeEach
    void seed() {
        sources.deleteAll();
        postings.deleteAll();

        JobPosting java = save("fp-java", "Senior Java Developer", "Stockholm", AtsVendor.TEAMTAILOR,
                "Vi soker en Java-utvecklare");
        save("fp-nurse", "Sjukskoterska", "Malmo", AtsVendor.VARBI, "Vard och omsorg");
        javaJobId = java.getId();

        JobPostingSource row = new JobPostingSource();
        row.setJobPosting(java);
        row.setSource(SourceId.JOBTECH);
        row.setSourceAdId("31404250");
        row.setSourceUrl("https://arbetsformedlingen.se/platsbanken/annonser/31404250");
        row.setFetchedAt(Instant.now());
        sources.save(row);
    }

    private JobPosting save(String fingerprint, String title, String municipality,
                            AtsVendor vendor, String description) {
        JobPosting job = new JobPosting();
        job.setFingerprint(fingerprint);
        job.setCanonicalUrl("https://example.se/" + fingerprint);
        job.setTitle(title);
        job.setEmployerName("Acme AB");
        job.setMunicipality(municipality);
        job.setDescription(description);
        job.setAtsVendor(vendor);
        job.setStatus(JobStatus.DISCOVERED);
        job.setPublishedAt(Instant.now());
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        return postings.save(job);
    }

    @Test
    void listsAllJobs() throws Exception {
        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void filtersByFreeTextQuery() throws Exception {
        mvc.perform(get("/api/jobs").param("q", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Senior Java Developer"));
    }

    @Test
    void filtersByMunicipality() throws Exception {
        mvc.perform(get("/api/jobs").param("municipality", "Malmo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Sjukskoterska"));
    }

    @Test
    void filtersByVendor() throws Exception {
        mvc.perform(get("/api/jobs").param("vendor", "TEAMTAILOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void paginates() throws Exception {
        mvc.perform(get("/api/jobs").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void returnsDetailWithContributingSources() throws Exception {
        mvc.perform(get("/api/jobs/" + javaJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Senior Java Developer"))
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].source").value("JOBTECH"))
                .andExpect(jsonPath("$.sources[0].sourceAdId").value("31404250"));
    }

    @Test
    void returns404ForUnknownJob() throws Exception {
        mvc.perform(get("/api/jobs/999999")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobControllerTest
```

Expected: 404s / compilation failure — the controller does not exist.

- [ ] **Step 3: Write the DTOs**

```java
// api/dto/JobSummaryDto.java
package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.AtsVendor;
import java.time.Instant;

public record JobSummaryDto(
        Long id, String title, String employerName, String municipality,
        AtsVendor atsVendor, String applyUrl, Instant publishedAt, Instant lastSeenAt) {
}
```

```java
// api/dto/JobSourceDto.java
package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.SourceId;
import java.time.Instant;

public record JobSourceDto(SourceId source, String sourceAdId, String sourceUrl, Instant fetchedAt) {
}
```

```java
// api/dto/JobDetailDto.java
package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.AtsVendor;
import java.time.Instant;
import java.util.List;

public record JobDetailDto(
        Long id, String title, String employerName, String employerOrgNumber,
        String municipality, String description, String language, AtsVendor atsVendor,
        String applyUrl, String canonicalUrl, Instant publishedAt, Instant deadlineAt,
        Instant firstSeenAt, Instant lastSeenAt, List<JobSourceDto> sources) {
}
```

```java
// api/dto/PageDto.java
package se.caiowain.jobseeker.api.dto;

import org.springframework.data.domain.Page;
import java.util.List;
import java.util.function.Function;

public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageDto<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageDto<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
```

- [ ] **Step 4: Write `JobQueryService`**

```java
package se.caiowain.jobseeker.api;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class JobQueryService {

    private final JobPostingRepository postings;

    public JobQueryService(JobPostingRepository postings) {
        this.postings = postings;
    }

    public Page<JobPosting> search(String query, String municipality, AtsVendor vendor,
                                   SourceId source, int page, int size) {
        Specification<JobPosting> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern),
                        cb.like(cb.lower(root.get("employerName")), pattern)));
            }
            if (municipality != null && !municipality.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("municipality")), municipality.toLowerCase()));
            }
            if (vendor != null) {
                predicates.add(cb.equal(root.get("atsVendor"), vendor));
            }
            if (source != null) {
                criteriaQuery.distinct(true);
                predicates.add(cb.equal(root.join("sources", jakarta.persistence.criteria.JoinType.INNER)
                        .get("source"), source));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        return postings.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt")));
    }
}
```

> The `source` filter joins a `sources` collection on `JobPosting`. Add it to the entity:
>
> ```java
> @OneToMany(mappedBy = "jobPosting", fetch = FetchType.LAZY)
> private java.util.List<JobPostingSource> sources = new java.util.ArrayList<>();
>
> public java.util.List<JobPostingSource> getSources() { return sources; }
> ```

- [ ] **Step 5: Write `JobController`**

```java
package se.caiowain.jobseeker.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.*;
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
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=JobControllerTest
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: add jobs REST API with filtering and pagination"
```

---

## Task 14: REST API for ingest, criteria and stats

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/api/{IngestController,CriteriaController,StatsController}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/api/dto/{IngestRunDto,CriteriaDto,StatsDto}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/config/WebConfig.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/api/IngestApiTest.java`

**Interfaces:**
- Consumes: `IngestOrchestrator`, `IngestRunRepository`, `SearchCriteriaRepository`, `JobPostingRepository`, `JobPostingSourceRepository`
- Produces: `POST /api/ingest/run`, `GET /api/ingest/runs`, `GET|POST /api/criteria`, `GET /api/stats`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/api/IngestApiTest.java`:

```java
package se.caiowain.jobseeker.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.*;
import se.caiowain.jobseeker.repo.*;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IngestApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IngestRunRepository runs;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;
    @Autowired SearchCriteriaRepository criteria;

    @BeforeEach
    void seed() {
        sources.deleteAll();
        postings.deleteAll();
        runs.deleteAll();

        IngestRun run = new IngestRun();
        run.setSource(SourceId.JOBTECH);
        run.setStartedAt(Instant.now());
        run.setFinishedAt(Instant.now());
        run.setFetched(10);
        run.setCreated(7);
        run.setMerged(3);
        run.setStatus(RunStatus.COMPLETED);
        runs.save(run);

        JobPosting job = new JobPosting();
        job.setFingerprint("fp-stats");
        job.setCanonicalUrl("https://example.se/1");
        job.setTitle("Java Developer");
        job.setAtsVendor(AtsVendor.TEAMTAILOR);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        postings.save(job);

        JobPostingSource row = new JobPostingSource();
        row.setJobPosting(job);
        row.setSource(SourceId.JOBTECH);
        row.setSourceAdId("a1");
        row.setFetchedAt(Instant.now());
        sources.save(row);
    }

    @Test
    void listsIngestRuns() throws Exception {
        mvc.perform(get("/api/ingest/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].source").value("JOBTECH"))
                .andExpect(jsonPath("$.content[0].fetched").value(10))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
    }

    @Test
    void returnsStats() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1))
                .andExpect(jsonPath("$.jobsBySource.JOBTECH").value(1))
                .andExpect(jsonPath("$.lastRun.source").value("JOBTECH"));
    }

    @Test
    void listsCriteriaSeededByMigration() throws Exception {
        mvc.perform(get("/api/criteria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void createsCriteria() throws Exception {
        long before = criteria.count();

        mvc.perform(post("/api/criteria")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Kotlin Goteborg","query":"kotlin",
                                 "municipalityCodes":"","municipalityNames":"Goteborg",
                                 "occupationFieldCodes":"apaJ_2ja_LuF","enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Kotlin Goteborg"));

        org.assertj.core.api.Assertions.assertThat(criteria.count()).isEqualTo(before + 1);
    }

    @Test
    void triggersAnIngestRun() throws Exception {
        // Sources will fail or return nothing against the network-less test environment;
        // the endpoint must still respond with the per-source run records.
        mvc.perform(post("/api/ingest/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=IngestApiTest
```

Expected: 404s — the controllers do not exist.

- [ ] **Step 3: Write the DTOs**

```java
// api/dto/IngestRunDto.java
package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.RunStatus;
import se.caiowain.jobseeker.domain.SourceId;
import java.time.Instant;

public record IngestRunDto(Long id, SourceId source, Instant startedAt, Instant finishedAt,
                           int fetched, int created, int merged, int errors,
                           RunStatus status, String message) {
}
```

```java
// api/dto/CriteriaDto.java
package se.caiowain.jobseeker.api.dto;

public record CriteriaDto(Long id, String name, String query, String municipalityCodes,
                          String municipalityNames, String occupationFieldCodes, boolean enabled) {
}
```

```java
// api/dto/StatsDto.java
package se.caiowain.jobseeker.api.dto;

import java.util.Map;

public record StatsDto(long totalJobs, Map<String, Long> jobsBySource,
                       Map<String, Long> jobsByVendor, long activeTenants,
                       IngestRunDto lastRun) {
}
```

- [ ] **Step 4: Write the controllers**

```java
// api/IngestController.java
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
```

```java
// api/CriteriaController.java
package se.caiowain.jobseeker.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.CriteriaDto;
import se.caiowain.jobseeker.domain.SearchCriteria;
import se.caiowain.jobseeker.repo.SearchCriteriaRepository;

import java.util.List;

@RestController
@RequestMapping("/api/criteria")
public class CriteriaController {

    private final SearchCriteriaRepository repository;

    public CriteriaController(SearchCriteriaRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CriteriaDto> list() {
        return repository.findAll().stream().map(CriteriaController::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<CriteriaDto> create(@RequestBody CriteriaDto request) {
        SearchCriteria entity = new SearchCriteria();
        entity.setName(request.name());
        entity.setQuery(request.query());
        entity.setMunicipalityCodes(request.municipalityCodes());
        entity.setMunicipalityNames(request.municipalityNames());
        entity.setOccupationFieldCodes(request.occupationFieldCodes());
        entity.setEnabled(request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(repository.save(entity)));
    }

    static CriteriaDto toDto(SearchCriteria c) {
        return new CriteriaDto(c.getId(), c.getName(), c.getQuery(), c.getMunicipalityCodes(),
                c.getMunicipalityNames(), c.getOccupationFieldCodes(), c.isEnabled());
    }
}
```

```java
// api/StatsController.java
package se.caiowain.jobseeker.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.caiowain.jobseeker.api.dto.StatsDto;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.*;

import java.util.Arrays;
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
        for (AtsVendor vendor : AtsVendor.values()) {
            long count = postings.findAll().stream()
                    .filter(job -> job.getAtsVendor() == vendor).count();
            if (count > 0) {
                byVendor.put(vendor.name(), count);
            }
        }

        long activeTenants = tenants.findAll().stream().filter(t -> t.isActive()).count();

        var lastRun = runs.findAllByOrderByStartedAtDesc(PageRequest.of(0, 1))
                .stream().findFirst().map(IngestController::toDto).orElse(null);

        return new StatsDto(postings.count(), bySource, byVendor, activeTenants, lastRun);
    }
}
```

- [ ] **Step 5: Allow the dev frontend origin**

`backend/src/main/java/se/caiowain/jobseeker/config/WebConfig.java`:

```java
package se.caiowain.jobseeker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=IngestApiTest
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
cd /home/cai/Projects/AgenticJobSeeker
git add backend/src
git commit -m "feat: add ingest, criteria and stats REST endpoints"
```

---

## Task 15: Frontend scaffold and API client

**Files:**
- Create: `frontend/` (Vite React TS scaffold)
- Create: `frontend/src/types.ts`, `frontend/src/api/client.ts`
- Modify: `frontend/vite.config.ts`, `frontend/src/index.css`

**Interfaces:**
- Consumes: the REST API from Tasks 13–14
- Produces: `fetchJobs`, `fetchJob`, `fetchStats`, `triggerIngest`, `fetchRuns` from `src/api/client.ts`

- [ ] **Step 1: Scaffold the project**

```bash
cd /home/cai/Projects/AgenticJobSeeker
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
npm install tailwindcss @tailwindcss/vite
npm install react-router-dom
```

- [ ] **Step 2: Configure Tailwind v4 and the API proxy**

`frontend/vite.config.ts`:

```ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

`frontend/src/index.css` — replace the whole file:

```css
@import "tailwindcss";
```

- [ ] **Step 3: Write the shared types**

`frontend/src/types.ts`:

```ts
export type SourceId = 'JOBTECH' | 'TEAMTAILOR' | 'VARBI'

export interface JobSummary {
  id: number
  title: string
  employerName: string | null
  municipality: string | null
  atsVendor: string
  applyUrl: string | null
  publishedAt: string | null
  lastSeenAt: string | null
}

export interface JobSource {
  source: SourceId
  sourceAdId: string
  sourceUrl: string | null
  fetchedAt: string
}

export interface JobDetail extends JobSummary {
  employerOrgNumber: string | null
  description: string | null
  language: string | null
  canonicalUrl: string
  deadlineAt: string | null
  firstSeenAt: string
  sources: JobSource[]
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface IngestRun {
  id: number
  source: SourceId
  startedAt: string
  finishedAt: string | null
  fetched: number
  created: number
  merged: number
  errors: number
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  message: string | null
}

export interface Stats {
  totalJobs: number
  jobsBySource: Record<string, number>
  jobsByVendor: Record<string, number>
  activeTenants: number
  lastRun: IngestRun | null
}

export interface JobFilters {
  q?: string
  municipality?: string
  vendor?: string
  source?: string
  page?: number
  size?: number
}
```

- [ ] **Step 4: Write the API client**

`frontend/src/api/client.ts`:

```ts
import type { JobDetail, JobFilters, IngestRun, JobSummary, Page, Stats } from '../types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export function fetchJobs(filters: JobFilters = {}): Promise<Page<JobSummary>> {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  })
  return request<Page<JobSummary>>(`/api/jobs?${params.toString()}`)
}

export function fetchJob(id: number): Promise<JobDetail> {
  return request<JobDetail>(`/api/jobs/${id}`)
}

export function fetchStats(): Promise<Stats> {
  return request<Stats>('/api/stats')
}

export function fetchRuns(): Promise<Page<IngestRun>> {
  return request<Page<IngestRun>>('/api/ingest/runs')
}

export function triggerIngest(): Promise<IngestRun[]> {
  return request<IngestRun[]>('/api/ingest/run', { method: 'POST' })
}
```

- [ ] **Step 5: Verify the build**

```bash
cd frontend && npm run build
```

Expected: build succeeds, `dist/` produced.

- [ ] **Step 6: Commit**

```bash
cd /home/cai/Projects/AgenticJobSeeker
git add frontend
git commit -m "feat: scaffold React frontend with Tailwind and API client"
```

---

## Task 16: Job list view with filters

**Files:**
- Create: `frontend/src/components/StatsHeader.tsx`, `frontend/src/components/JobFilters.tsx`, `frontend/src/components/SourceBadge.tsx`
- Create: `frontend/src/pages/JobList.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/main.tsx`

**Interfaces:**
- Consumes: `fetchJobs`, `fetchStats`, `triggerIngest` from Task 15
- Produces: route `/` rendering the job list

- [ ] **Step 1: Write `SourceBadge`**

`frontend/src/components/SourceBadge.tsx`:

```tsx
const TONE: Record<string, string> = {
  JOBTECH: 'bg-blue-100 text-blue-800 ring-blue-600/20',
  TEAMTAILOR: 'bg-emerald-100 text-emerald-800 ring-emerald-600/20',
  VARBI: 'bg-amber-100 text-amber-800 ring-amber-600/20',
}

export function SourceBadge({ label }: { label: string }) {
  const tone = TONE[label] ?? 'bg-slate-100 text-slate-700 ring-slate-500/20'
  return (
    <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${tone}`}>
      {label}
    </span>
  )
}
```

- [ ] **Step 2: Write `StatsHeader`**

`frontend/src/components/StatsHeader.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { fetchStats, triggerIngest } from '../api/client'
import type { Stats } from '../types'

export function StatsHeader({ onIngested }: { onIngested: () => void }) {
  const [stats, setStats] = useState<Stats | null>(null)
  const [running, setRunning] = useState(false)

  const load = () => { fetchStats().then(setStats).catch(() => setStats(null)) }

  useEffect(load, [])

  const runIngest = async () => {
    setRunning(true)
    try {
      await triggerIngest()
      load()
      onIngested()
    } finally {
      setRunning(false)
    }
  }

  return (
    <header className="mb-6 flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-4">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Job Discovery</h1>
        <p className="mt-1 text-sm text-slate-600">
          {stats
            ? `${stats.totalJobs} jobs · ${stats.activeTenants} active ATS tenants`
            : 'Loading…'}
        </p>
        {stats?.lastRun && (
          <p className="mt-1 text-xs text-slate-500">
            Last run: {stats.lastRun.source} — {stats.lastRun.status} (fetched {stats.lastRun.fetched},
            new {stats.lastRun.created}, merged {stats.lastRun.merged})
          </p>
        )}
      </div>
      <button
        onClick={runIngest}
        disabled={running}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
      >
        {running ? 'Running…' : 'Run ingest'}
      </button>
    </header>
  )
}
```

- [ ] **Step 3: Write `JobFilters`**

`frontend/src/components/JobFilters.tsx`:

```tsx
import type { JobFilters as Filters } from '../types'

interface Props {
  value: Filters
  onChange: (next: Filters) => void
}

export function JobFilters({ value, onChange }: Props) {
  const set = (patch: Partial<Filters>) => onChange({ ...value, ...patch, page: 0 })

  return (
    <div className="mb-4 flex flex-wrap gap-3">
      <input
        type="search"
        placeholder="Search title, employer or description…"
        value={value.q ?? ''}
        onChange={(e) => set({ q: e.target.value })}
        className="min-w-64 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm"
      />
      <input
        type="text"
        placeholder="Municipality"
        value={value.municipality ?? ''}
        onChange={(e) => set({ municipality: e.target.value })}
        className="w-44 rounded-md border border-slate-300 px-3 py-2 text-sm"
      />
      <select
        value={value.source ?? ''}
        onChange={(e) => set({ source: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm"
      >
        <option value="">All sources</option>
        <option value="JOBTECH">JobTech</option>
        <option value="TEAMTAILOR">Teamtailor</option>
        <option value="VARBI">Varbi</option>
      </select>
    </div>
  )
}
```

- [ ] **Step 4: Write `JobList`**

`frontend/src/pages/JobList.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchJobs } from '../api/client'
import { JobFilters } from '../components/JobFilters'
import { SourceBadge } from '../components/SourceBadge'
import { StatsHeader } from '../components/StatsHeader'
import type { JobFilters as Filters, JobSummary, Page } from '../types'

export function JobList() {
  const [filters, setFilters] = useState<Filters>({ page: 0, size: 20 })
  const [data, setData] = useState<Page<JobSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    fetchJobs(filters)
      .then((page) => { setData(page); setError(null) })
      .catch((e: Error) => setError(e.message))
  }

  useEffect(load, [filters])

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <StatsHeader onIngested={load} />
      <JobFilters value={filters} onChange={setFilters} />

      {error && <p className="rounded-md bg-red-50 p-3 text-sm text-red-700">Failed to load: {error}</p>}

      {data && data.content.length === 0 && (
        <p className="py-12 text-center text-sm text-slate-500">
          No jobs yet. Run an ingest to pull postings from JobTech.
        </p>
      )}

      <ul className="divide-y divide-slate-200">
        {data?.content.map((job) => (
          <li key={job.id} className="py-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <Link to={`/jobs/${job.id}`} className="text-base font-medium text-slate-900 hover:underline">
                  {job.title}
                </Link>
                <p className="mt-0.5 truncate text-sm text-slate-600">
                  {job.employerName ?? 'Unknown employer'}
                  {job.municipality ? ` · ${job.municipality}` : ''}
                </p>
              </div>
              <SourceBadge label={job.atsVendor} />
            </div>
          </li>
        ))}
      </ul>

      {data && data.totalPages > 1 && (
        <div className="mt-6 flex items-center justify-between text-sm">
          <button
            disabled={data.page === 0}
            onClick={() => setFilters({ ...filters, page: data.page - 1 })}
            className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
          >
            Previous
          </button>
          <span className="text-slate-600">
            Page {data.page + 1} of {data.totalPages} · {data.totalElements} jobs
          </span>
          <button
            disabled={data.page + 1 >= data.totalPages}
            onClick={() => setFilters({ ...filters, page: data.page + 1 })}
            className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 5: Wire the router**

`frontend/src/App.tsx`:

```tsx
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { JobList } from './pages/JobList'
import { JobDetail } from './pages/JobDetail'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<JobList />} />
        <Route path="/jobs/:id" element={<JobDetail />} />
      </Routes>
    </BrowserRouter>
  )
}
```

`frontend/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

Delete the unused scaffold files:

```bash
rm -f frontend/src/App.css frontend/src/assets/react.svg
```

- [ ] **Step 6: Commit (build verified in Task 17, which adds `JobDetail`)**

```bash
git add frontend/src
git commit -m "feat: add job list view with filters and stats header"
```

---

## Task 17: Job detail view

**Files:**
- Create: `frontend/src/pages/JobDetail.tsx`

**Interfaces:**
- Consumes: `fetchJob` from Task 15
- Produces: route `/jobs/:id`

- [ ] **Step 1: Write `JobDetail`**

`frontend/src/pages/JobDetail.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { fetchJob } from '../api/client'
import { SourceBadge } from '../components/SourceBadge'
import type { JobDetail as Job } from '../types'

export function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const [job, setJob] = useState<Job | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchJob(Number(id))
      .then((j) => { setJob(j); setError(null) })
      .catch((e: Error) => setError(e.message))
  }, [id])

  if (error) {
    return <p className="mx-auto max-w-3xl px-6 py-8 text-sm text-red-700">Failed to load: {error}</p>
  }
  if (!job) {
    return <p className="mx-auto max-w-3xl px-6 py-8 text-sm text-slate-500">Loading…</p>
  }

  return (
    <div className="mx-auto max-w-3xl px-6 py-8">
      <Link to="/" className="text-sm text-slate-600 hover:underline">← Back to jobs</Link>

      <h1 className="mt-4 text-2xl font-semibold text-slate-900">{job.title}</h1>
      <p className="mt-1 text-sm text-slate-600">
        {job.employerName ?? 'Unknown employer'}
        {job.municipality ? ` · ${job.municipality}` : ''}
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <SourceBadge label={job.atsVendor} />
        {job.sources.map((s) => <SourceBadge key={s.sourceAdId} label={s.source} />)}
      </div>

      {job.applyUrl && (
        <a
          href={job.applyUrl}
          target="_blank"
          rel="noreferrer"
          className="mt-5 inline-block rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700"
        >
          Apply on employer site
        </a>
      )}

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">Description</h2>
        <p className="whitespace-pre-wrap text-sm leading-relaxed text-slate-800">
          {job.description ?? 'No description captured.'}
        </p>
      </section>

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Seen in {job.sources.length} source{job.sources.length === 1 ? '' : 's'}
        </h2>
        <ul className="space-y-2 text-sm">
          {job.sources.map((s) => (
            <li key={`${s.source}-${s.sourceAdId}`} className="flex items-center gap-2">
              <SourceBadge label={s.source} />
              {s.sourceUrl
                ? <a href={s.sourceUrl} target="_blank" rel="noreferrer"
                     className="truncate text-slate-600 hover:underline">{s.sourceUrl}</a>
                : <span className="text-slate-500">{s.sourceAdId}</span>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
```

- [ ] **Step 2: Build and typecheck**

```bash
cd frontend && npm run build
```

Expected: build succeeds with no TypeScript errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src
git commit -m "feat: add job detail view showing all contributing sources"
```

---

## Task 18: End-to-end verification and documentation

**Files:**
- Create: `README.md`
- Test: manual end-to-end run against the live APIs

**Interfaces:**
- Consumes: everything
- Produces: a verified running system and setup documentation

- [ ] **Step 1: Run the full backend suite**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: all tests PASS.

- [ ] **Step 2: Start PostgreSQL and the backend**

```bash
cd /home/cai/Projects/AgenticJobSeeker
docker compose up -d
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw spring-boot:run
```

Wait for `Started JobseekerApplication`.

- [ ] **Step 3: Trigger a real ingest and verify jobs land**

In a second shell:

```bash
curl -s -X POST http://localhost:8080/api/ingest/run | python3 -m json.tool
curl -s "http://localhost:8080/api/stats" | python3 -m json.tool
curl -s "http://localhost:8080/api/jobs?q=java&size=3" | python3 -m json.tool
```

Expected:
- The ingest response is an array with one entry per source, each `COMPLETED`.
- `totalJobs` is greater than zero.
- The JobTech run reports a non-zero `fetched`.
- `activeTenants` is greater than zero, proving tenant discovery seeded itself from JobTech apply URLs.

- [ ] **Step 4: Verify cross-source merging happened**

```bash
docker exec jobseeker-postgres psql -U jobseeker -d job_db -c "
  SELECT p.id, left(p.title, 45) AS title, count(s.id) AS source_count
  FROM job_posting p JOIN job_posting_source s ON s.job_posting_id = p.id
  GROUP BY p.id, p.title HAVING count(s.id) > 1
  ORDER BY source_count DESC LIMIT 10;"
```

Expected: at least one row where `source_count` is 2 or more — a job seen in both JobTech and an ATS feed. If the second ingest run has not happened yet, run `POST /api/ingest/run` once more first, since Teamtailor and Varbi tenants only exist after JobTech has seeded them.

- [ ] **Step 5: Verify the dashboard**

```bash
cd frontend && npm run dev
```

Open `http://localhost:5173`. Confirm:
- the stats header shows a job count and active tenant count
- the list renders jobs with vendor badges
- the search box filters results
- clicking a job opens the detail view with its source list

- [ ] **Step 6: Write the README**

`README.md`:

````markdown
# AgenticJobSeeker

Autonomous job discovery for the Swedish IT market. Slice 1 — the spine — discovers
job postings from three sources, deduplicates them across sources, stores them in
PostgreSQL and renders them in a dashboard.

## Sources

| Source | Type | Access |
|---|---|---|
| JobTech (Arbetsförmedlingen) | Official API | Keyless, open government data |
| Teamtailor | JSON Feed per career site | Keyless, `robots.txt` permits `ai-input` |
| Varbi | RSS per tenant | Keyless |

Teamtailor and Varbi tenants are **discovered automatically** from the apply URLs in
JobTech ads — there is no tenant list to maintain.

## Requirements

- Java 21
- Docker (for PostgreSQL)
- Node 22+

Maven is **not** required; use the bundled wrapper.

## Running

```bash
docker compose up -d                 # PostgreSQL on :5432

cd backend
export JAVA_HOME=/usr/lib/jvm/default
./mvnw spring-boot:run               # API on :8080

cd ../frontend
npm install
npm run dev                          # dashboard on :5173
```

Trigger a discovery run:

```bash
curl -X POST http://localhost:8080/api/ingest/run
```

## Testing

```bash
cd backend && ./mvnw test            # Testcontainers + WireMock; no live network calls
cd frontend && npm run build         # typecheck + build
```

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/jobs` | Paged, filtered job list |
| `GET` | `/api/jobs/{id}` | Job detail with all contributing sources |
| `POST` | `/api/ingest/run` | Trigger a discovery run |
| `GET` | `/api/ingest/runs` | Run history |
| `GET`/`POST` | `/api/criteria` | Manage search criteria |
| `GET` | `/api/stats` | Dashboard counts |

## Design documents

- Spec: `docs/superpowers/specs/2026-08-27-job-discovery-spine-design.md`
- Plan: `docs/superpowers/plans/2026-08-27-job-discovery-spine.md`

## Not in this slice

AI CV/cover-letter tailoring (Slice 2), Playwright application submission (Slice 3),
and metrics/settings (Slice 4).
````

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: add README with setup and verification instructions"
```

---

## Done criteria

1. `./mvnw test` passes with no live network calls.
2. `docker compose up -d` plus `./mvnw spring-boot:run` yields a running API.
3. `POST /api/ingest/run` pulls real Swedish IT jobs into PostgreSQL.
4. Teamtailor and Varbi tenants appear in `ats_tenant` without manual configuration.
5. At least one `job_posting` has two or more `job_posting_source` rows.
6. The dashboard lists, filters and displays jobs at `http://localhost:5173`.
7. A failing source yields a `FAILED` `ingest_run` row while other sources still complete.
