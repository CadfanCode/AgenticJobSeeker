# Faithful Preview and Immutable Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** See a tailored application exactly as its recipient will, approve it deliberately, and keep an immutable record of what was approved — including the ad it answered.

**Architecture:** One HTML string per document is built by a pure builder. The preview endpoint returns that string; the renderer prints that same string to PDF through headless Chromium. Preview and artifact therefore cannot drift, because there is one implementation rather than two that agree by coincidence. Approval renders both documents, hashes them, and writes them into an archive table that outlives the application it came from.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Testcontainers, Playwright (headless Chromium), PDFBox 3.0.8 (already present, used here to read rendered PDFs back), React 19 + Vite 8 + Tailwind 4.

**Spec:** `docs/superpowers/specs/2026-08-31-faithful-preview-and-archive-design.md`

## Global Constraints

Everything from Slices 1, 2a, 2b and 2d still applies. Repeated because they are easy to get wrong:

- **Spring Boot version is exactly `4.1.1`.** Never `4.1.1.RELEASE`.
- **Jackson 3** — import `tools.jackson.databind.*`, never `com.fasterxml.jackson.databind.*`.
- **`@AutoConfigureMockMvc` is `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.**
- **Maven is not installed.** Build with `./mvnw` from `backend/` after `export JAVA_HOME=/usr/lib/jvm/default`.
- **PostgreSQL is on port 5433.** Tests use Testcontainers `postgres:16-alpine` via `AbstractIntegrationTest`.
- Hibernate runs `ddl-auto: validate`; entity mappings must match the migration exactly.
- **Entity `byte[]` uses `columnDefinition = "bytea"`, never `@Lob`** (which maps to `oid`). Slice 2a set this precedent on `cv_document.content`.
- **No test may reach the network.** That includes a Playwright browser download: Chromium is a developer prerequisite installed outside the build, and browser-dependent tests `assumeTrue` it is present and skip when it is not.
- **The document HTML must reference no external resource.** No CDN fonts, no remote images, no stylesheets fetched at render time. Rendering has to be deterministic and work offline, and a font that fails to load silently changes the document. Use a system font stack and inline CSS.
- **No code may read, navigate, follow or submit `apply_url`.** The archive stores it as text for the candidate's reference; nothing dereferences it.
- **The renderer adds layout, never content.** Every string an employer reads originates in a profile row or in the ad. A bullet in the HTML must be byte-identical to a bullet in `cv_experience_bullet`.
- **TDD:** failing test, watch it fail, minimal implementation, watch it pass, commit.
- Every task ends with a commit.

### Two decisions taken after the spec was written

**1. The CV renders every experience and every bullet, with matched bullets ordered first within each experience.** The spec said "the bullets 2b selected in the order it ranked them", which describes something that does not exist: `ApplicationAssembler` receives the model's `rankedBulletIds` and discards them, persisting only evidence. Filtering to evidence alone would also silently drop real work history — with the current profile it would render a two-bullet CV. So: nothing is hidden, and relevance is expressed through ordering. Within an experience, a bullet whose text matches an evidence snapshot sorts before one that does not; ties keep `ordinal ASC`.

**2. `ArchiveController` serves `POST /api/applications/{id}/approve`, not `TailoringController`.** The path is unchanged, as the spec requires. Putting the mapping in the archive package keeps the dependency one-way — `archive` reads `tailor.domain` and `tailor.repo`, and nothing in `tailor` references `archive`. `TailoringService.approve` moves here rather than being called from here.

---

## File Structure

**Backend** (`backend/src/main/java/se/caiowain/jobseeker/`):

| Path | Responsibility |
|---|---|
| `render/CvContent.java` | Pure model: header, experiences, ordered bullets. **Not** `CvDocument` — `profile.domain.CvDocument` is the uploaded PDF |
| `render/LetterContent.java` | Pure model: sender, recipient, date, body |
| `render/ApplicationDocument.java` | Pure: `TailoredApplication` + `CvProfile` → the two models |
| `render/DocumentHtmlBuilder.java` | Pure: model → one standalone HTML string |
| `render/PdfRenderer.java` | The only component that drives a browser |
| `render/RendererUnavailableException.java` | No Chromium → 503 |
| `render/RenderConfig.java` | Beans for the pure components |
| `archive/domain/ApplicationArchive.java` | The frozen row |
| `archive/repo/ApplicationArchiveRepository.java` | Spring Data repository |
| `archive/ArchiveService.java` | Approve: render, hash, persist, freeze |
| `archive/ApplicationNotDraftException.java` | Approving a non-draft → 409 |
| `archive/api/ArchiveController.java` | Preview, approve, archive read endpoints |
| `archive/api/dto/*.java` | Response records |

**Modified:**

| Path | Change |
|---|---|
| `backend/pom.xml` | Add the Playwright dependency |
| `tailor/api/TailoringController.java` | Remove the `approve` mapping (moves to `ArchiveController`) |
| `tailor/TailoringService.java` | Remove `approve` (moves to `ArchiveService`) |
| `api/ApiExceptionHandler.java` | Map `RendererUnavailableException` → 503, `ApplicationNotDraftException` → 409 |

**Migration:** `backend/src/main/resources/db/migration/V8__application_archive.sql`

**Frontend** (`frontend/src/`):

| Path | Responsibility |
|---|---|
| `archiveTypes.ts` | Archive types |
| `api/archiveClient.ts` | Archive and preview calls |
| `components/DocumentPreview.tsx` | The CV/letter tabs and their iframes |
| `pages/ArchivePage.tsx` | The interview view |

**Modified:** `pages/ApplicationReview.tsx`, `App.tsx`, `pages/JobList.tsx` (nav link).

---

## Task 1: The `V8` schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__application_archive.sql`
- Test: `backend/src/test/java/se/caiowain/jobseeker/archive/ArchiveSchemaMigrationTest.java`

**Interfaces:**
- Produces: table `application_archive` with nullable `SET NULL` foreign keys and no unique constraint on `tailored_application_id`.

- [ ] **Step 1: Write the failing migration test**

Create `backend/src/test/java/se/caiowain/jobseeker/archive/ArchiveSchemaMigrationTest.java`:

```java
package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ArchiveSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTheArchiveTable() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains("application_archive");
    }

    @Test
    void everyForeignKeyIsNullableAndSetsNullOnDelete() {
        // The archive must survive the application, the posting and the profile it came from.
        // A cascade here would let a re-tailor delete history, which is the one thing this
        // table exists to prevent.
        List<String> nullability = jdbc.queryForList("""
                select is_nullable from information_schema.columns
                where table_name = 'application_archive'
                  and column_name in
                      ('tailored_application_id', 'job_posting_id', 'cv_profile_id')
                """, String.class);

        assertThat(nullability).hasSize(3).allMatch("YES"::equals);

        Integer setNullRules = jdbc.queryForObject("""
                select count(*) from information_schema.referential_constraints rc
                join information_schema.table_constraints tc
                  on rc.constraint_name = tc.constraint_name
                where tc.table_name = 'application_archive'
                  and rc.delete_rule = 'SET NULL'
                """, Integer.class);

        assertThat(setNullRules).isEqualTo(3);
    }

    @Test
    void oneJobMayBeArchivedMoreThanOnce() {
        // Discard, re-tailor and approve again must produce a second row rather than
        // overwriting the first. A unique constraint here would lose an application that
        // was genuinely sent.
        Integer uniqueOnApplication = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'application_archive'
                  and indexdef like '%UNIQUE%tailored_application_id%'
                """, Integer.class);

        assertThat(uniqueOnApplication).isZero();
    }

    @Test
    void thePdfColumnsAreByteaAndNotNull() {
        // bytea, never oid: @Lob maps to oid and Slice 2a already hit that on cv_document.
        List<String> types = jdbc.queryForList("""
                select data_type from information_schema.columns
                where table_name = 'application_archive'
                  and column_name in ('cv_pdf', 'letter_pdf')
                """, String.class);

        assertThat(types).hasSize(2).allMatch("bytea"::equals);

        List<String> nullability = jdbc.queryForList("""
                select is_nullable from information_schema.columns
                where table_name = 'application_archive'
                  and column_name in ('cv_pdf', 'letter_pdf', 'cv_pdf_sha256', 'letter_pdf_sha256')
                """, String.class);

        assertThat(nullability).hasSize(4).allMatch("NO"::equals);
    }

    @Test
    void theAdIsCopiedIntoTheArchiveRatherThanReferenced() {
        // JobMergeService overwrites job_posting.description on re-ingest, so a reference
        // would show a future reader a different job than the one that was answered.
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'application_archive'
                """, String.class);

        assertThat(columns).contains(
                "job_title", "employer_name", "job_canonical_url", "job_apply_url",
                "job_description_text", "letter_text");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ArchiveSchemaMigrationTest
```

Expected: FAIL — `application_archive` does not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V8__application_archive.sql`:

```sql
-- Slice 2c — the immutable record of an approved application.
--
-- PRESERVED, in the sense Slice 2d established for job_triage: tailored_application is
-- working state that a re-tailor may replace outright, and every approval must still
-- survive that. Hence nullable foreign keys with ON DELETE SET NULL — traceability, never
-- dependency — and its own copy of everything needed to read the row standalone.

CREATE TABLE application_archive (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,

    -- Traceability only. Null once the thing they point at is gone; the row stays readable.
    tailored_application_id BIGINT REFERENCES tailored_application (id) ON DELETE SET NULL,
    job_posting_id          BIGINT REFERENCES job_posting (id)          ON DELETE SET NULL,
    cv_profile_id           BIGINT REFERENCES cv_profile (id)           ON DELETE SET NULL,

    -- The ad, copied. JobMergeService overwrites job_posting.description whenever a richer
    -- version of the same ad is found, and employers delete postings.
    job_title               VARCHAR(512)  NOT NULL,
    employer_name           VARCHAR(512),
    job_canonical_url       VARCHAR(1024),
    -- Stored for the candidate to read. Nothing in this slice dereferences it.
    job_apply_url           VARCHAR(2048),
    job_description_text    TEXT,

    -- The letter as text, for a form field that wants text rather than a file.
    letter_text             TEXT,

    -- bytea, never oid. @Lob maps to oid; Slice 2a settled this on cv_document.content.
    cv_pdf                  BYTEA        NOT NULL,
    cv_pdf_sha256           VARCHAR(64)  NOT NULL,
    letter_pdf              BYTEA        NOT NULL,
    letter_pdf_sha256       VARCHAR(64)  NOT NULL,

    coverage_percent        INTEGER      NOT NULL DEFAULT 0,
    rendered_by             VARCHAR(128),
    approved_at             TIMESTAMPTZ  NOT NULL
);

-- Deliberately NOT unique on tailored_application_id: discarding an approved application and
-- approving a replacement must add a row, not overwrite one.
CREATE INDEX ix_application_archive_approved  ON application_archive (approved_at DESC);
CREATE INDEX ix_application_archive_app       ON application_archive (tailored_application_id);
```

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ArchiveSchemaMigrationTest
```

Expected: PASS, all five tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__application_archive.sql \
        backend/src/test/java/se/caiowain/jobseeker/archive/ArchiveSchemaMigrationTest.java
git commit -m "feat: add V8 application archive schema"
```

---

## Task 2: The archive entity and repository

**Files:**
- Create: `archive/domain/ApplicationArchive.java`, `archive/repo/ApplicationArchiveRepository.java`
- Test: `src/test/java/se/caiowain/jobseeker/archive/ApplicationArchivePersistenceTest.java`

**Interfaces:**
- Consumes: `V8` (Task 1); `TailoredApplication`, `JobPosting`, `CvProfile`.
- Produces: `ApplicationArchive` with getters/setters for every column; `ApplicationArchiveRepository extends JpaRepository<ApplicationArchive, Long>` with `Page<ApplicationArchive> findAllByOrderByApprovedAtDesc(Pageable)`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/archive/ApplicationArchivePersistenceTest.java`:

```java
package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApplicationArchivePersistenceTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationArchiveRepository archives;

    private ApplicationArchive minimal(String title, Instant approvedAt) {
        ApplicationArchive archive = new ApplicationArchive();
        archive.setJobTitle(title);
        archive.setEmployerName("Example AB");
        archive.setJobDescriptionText("Vi söker en utvecklare med erfarenhet av Java.");
        archive.setLetterText("Hej,\n\nJag söker tjänsten.");
        archive.setCvPdf("%PDF-cv".getBytes(StandardCharsets.UTF_8));
        archive.setCvPdfSha256("a".repeat(64));
        archive.setLetterPdf("%PDF-letter".getBytes(StandardCharsets.UTF_8));
        archive.setLetterPdfSha256("b".repeat(64));
        archive.setCoveragePercent(67);
        archive.setApprovedAt(approvedAt);
        return archive;
    }

    @Test
    void roundTripsWithoutAnyApplicationAttached() {
        // The archive must be readable on its own — that is the entire point of the table.
        archives.deleteAllInBatch();

        ApplicationArchive saved = archives.saveAndFlush(
                minimal("Backend Developer", Instant.parse("2026-08-31T09:00:00Z")));

        ApplicationArchive reloaded = archives.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getJobTitle()).isEqualTo("Backend Developer");
        assertThat(reloaded.getJobDescriptionText()).contains("erfarenhet av Java");
        assertThat(reloaded.getTailoredApplication()).isNull();
    }

    @Test
    void storesPdfBytesVerbatim() {
        archives.deleteAllInBatch();
        byte[] cv = "%PDF-1.7 pretend this is a real cv".getBytes(StandardCharsets.UTF_8);

        ApplicationArchive archive = minimal("Utvecklare", Instant.parse("2026-08-31T09:00:00Z"));
        archive.setCvPdf(cv);
        Long id = archives.saveAndFlush(archive).getId();

        assertThat(archives.findById(id).orElseThrow().getCvPdf()).isEqualTo(cv);
    }

    @Test
    void listsNewestFirst() {
        archives.deleteAllInBatch();
        archives.saveAndFlush(minimal("Older", Instant.parse("2026-08-01T09:00:00Z")));
        archives.saveAndFlush(minimal("Newer", Instant.parse("2026-08-30T09:00:00Z")));

        var page = archives.findAllByOrderByApprovedAtDesc(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ApplicationArchive::getJobTitle)
                .containsExactly("Newer", "Older");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ApplicationArchivePersistenceTest
```

Expected: compilation failure — the archive classes do not exist.

- [ ] **Step 3: Write the entity**

Create `backend/src/main/java/se/caiowain/jobseeker/archive/domain/ApplicationArchive.java`:

```java
package se.caiowain.jobseeker.archive.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.time.Instant;

/**
 * What was approved, frozen at the moment of approval.
 *
 * <p>Every association is optional and set to null when its target is deleted. That is
 * deliberate: {@code tailored_application} is working state a re-tailor may replace outright,
 * job postings are removed by employers, and a CV profile is superseded on re-upload. An
 * archive that any of those could delete would not be an archive, so the row carries its own
 * copy of everything needed to read it — including the ad, because
 * {@code JobMergeService} overwrites {@code job_posting.description} on re-ingest.
 *
 * <p>{@code jobApplyUrl} is stored for the candidate to read. Nothing dereferences it.
 */
@Entity
@Table(name = "application_archive")
public class ApplicationArchive {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tailored_application_id")
    private TailoredApplication tailoredApplication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_posting_id")
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cv_profile_id")
    private CvProfile cvProfile;

    @Column(name = "job_title", nullable = false, length = 512) private String jobTitle;
    @Column(name = "employer_name", length = 512) private String employerName;
    @Column(name = "job_canonical_url", length = 1024) private String jobCanonicalUrl;
    @Column(name = "job_apply_url", length = 2048) private String jobApplyUrl;
    @Column(name = "job_description_text", columnDefinition = "text") private String jobDescriptionText;

    @Column(name = "letter_text", columnDefinition = "text") private String letterText;

    /** Plain byte[] maps to bytea; @Lob would map to oid (large object). */
    @Column(name = "cv_pdf", nullable = false, columnDefinition = "bytea") private byte[] cvPdf;
    @Column(name = "cv_pdf_sha256", nullable = false, length = 64) private String cvPdfSha256;
    @Column(name = "letter_pdf", nullable = false, columnDefinition = "bytea") private byte[] letterPdf;
    @Column(name = "letter_pdf_sha256", nullable = false, length = 64) private String letterPdfSha256;

    @Column(name = "coverage_percent", nullable = false) private int coveragePercent;
    @Column(name = "rendered_by", length = 128) private String renderedBy;
    @Column(name = "approved_at", nullable = false) private Instant approvedAt;

    public Long getId() { return id; }
    public TailoredApplication getTailoredApplication() { return tailoredApplication; }
    public void setTailoredApplication(TailoredApplication v) { this.tailoredApplication = v; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }
    public String getEmployerName() { return employerName; }
    public void setEmployerName(String employerName) { this.employerName = employerName; }
    public String getJobCanonicalUrl() { return jobCanonicalUrl; }
    public void setJobCanonicalUrl(String v) { this.jobCanonicalUrl = v; }
    public String getJobApplyUrl() { return jobApplyUrl; }
    public void setJobApplyUrl(String jobApplyUrl) { this.jobApplyUrl = jobApplyUrl; }
    public String getJobDescriptionText() { return jobDescriptionText; }
    public void setJobDescriptionText(String v) { this.jobDescriptionText = v; }
    public String getLetterText() { return letterText; }
    public void setLetterText(String letterText) { this.letterText = letterText; }
    public byte[] getCvPdf() { return cvPdf; }
    public void setCvPdf(byte[] cvPdf) { this.cvPdf = cvPdf; }
    public String getCvPdfSha256() { return cvPdfSha256; }
    public void setCvPdfSha256(String v) { this.cvPdfSha256 = v; }
    public byte[] getLetterPdf() { return letterPdf; }
    public void setLetterPdf(byte[] letterPdf) { this.letterPdf = letterPdf; }
    public String getLetterPdfSha256() { return letterPdfSha256; }
    public void setLetterPdfSha256(String v) { this.letterPdfSha256 = v; }
    public int getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(int coveragePercent) { this.coveragePercent = coveragePercent; }
    public String getRenderedBy() { return renderedBy; }
    public void setRenderedBy(String renderedBy) { this.renderedBy = renderedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
```

- [ ] **Step 4: Write the repository**

Create `backend/src/main/java/se/caiowain/jobseeker/archive/repo/ApplicationArchiveRepository.java`:

```java
package se.caiowain.jobseeker.archive.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;

public interface ApplicationArchiveRepository extends JpaRepository<ApplicationArchive, Long> {
    Page<ApplicationArchive> findAllByOrderByApprovedAtDesc(Pageable pageable);
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ApplicationArchivePersistenceTest
```

Expected: PASS, all three tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/archive backend/src/test/java/se/caiowain/jobseeker/archive
git commit -m "feat: add the application archive entity and repository"
```

---

## Task 3: The document model

Pure translation from stored rows to what a reader sees. No Spring, no I/O, no HTML.

**Note the naming:** `CvDocument` is already taken — `profile.domain.CvDocument` is the uploaded PDF from Slice 2a. These are `CvContent` and `LetterContent`.

**Files:**
- Create: `render/CvContent.java`, `render/LetterContent.java`, `render/ApplicationDocument.java`
- Test: `src/test/java/se/caiowain/jobseeker/render/ApplicationDocumentTest.java`

**Interfaces:**
- Consumes: `TailoredApplication` (`getRequirements()`, `getLetterProse()`, `getJobPosting()`), `ApplicationRequirement.getEvidence()`, `ApplicationEvidence.getCvExperienceBulletId()` / `getBulletText()`, `CvProfile` (`getExperiences()`, `getSkills()`, header fields), `CvExperience.getBullets()`, `CvExperienceBullet.getId()` / `getText()` / `getOrdinal()`.
- Produces: `record CvContent(String fullName, String headline, String email, String phone, String location, String summary, List<String> skills, List<CvContent.Experience> experiences)` with `record Experience(String employer, String title, String startDate, String endDate, String location, List<String> bullets)`;
  `record LetterContent(String candidateName, String candidateEmail, String candidatePhone, String candidateLocation, String employerName, String jobTitle, LocalDate date, String body)`;
  `ApplicationDocument.cv(TailoredApplication, CvProfile) -> CvContent` and `ApplicationDocument.letter(TailoredApplication, CvProfile, LocalDate) -> LetterContent`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/render/ApplicationDocumentTest.java`:

```java
package se.caiowain.jobseeker.render;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.ApplicationEvidence;
import se.caiowain.jobseeker.tailor.domain.ApplicationRequirement;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationDocumentTest {

    private final ApplicationDocument document = new ApplicationDocument();

    private CvProfile profileWithBullets(String... bulletTexts) {
        CvProfile profile = new CvProfile();
        profile.setFullName("Cai Wain");
        profile.setHeadline("Senior Software Engineer");
        profile.setEmail("cai@example.com");
        profile.setLocation("Stockholm, Sweden");

        CvExperience experience = new CvExperience();
        experience.setEmployer("Acme AB");
        experience.setTitle("Backend Developer");
        experience.setStartDate("2022");
        experience.setEndDate("2026");
        experience.setOrdinal(0);

        int ordinal = 0;
        for (String text : bulletTexts) {
            CvExperienceBullet bullet = new CvExperienceBullet();
            bullet.setText(text);
            bullet.setOrdinal(ordinal++);
            experience.addBullet(bullet);
        }
        profile.addExperience(experience);
        return profile;
    }

    private TailoredApplication applicationCiting(String... evidenceTexts) {
        JobPosting job = new JobPosting();
        job.setTitle("Plattformsingenjör");
        job.setEmployerName("Example AB");

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);

        ApplicationRequirement requirement = new ApplicationRequirement();
        requirement.setText("erfarenhet av Java");
        requirement.setOrdinal(0);
        int ordinal = 0;
        for (String text : evidenceTexts) {
            ApplicationEvidence evidence = new ApplicationEvidence();
            evidence.setBulletText(text);
            evidence.setOrdinal(ordinal++);
            requirement.addEvidence(evidence);
        }
        application.addRequirement(requirement);
        return application;
    }

    @Test
    void keepsEveryBulletAndPutsMatchedOnesFirst() {
        // Filtering to matched bullets alone would silently drop real work history. Ordering
        // expresses relevance without hiding anything.
        CvProfile profile = profileWithBullets("Wrote documentation", "Built REST APIs in Java",
                "Ran the release process");
        TailoredApplication application = applicationCiting("Built REST APIs in Java");

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences()).hasSize(1);
        assertThat(cv.experiences().getFirst().bullets()).containsExactly(
                "Built REST APIs in Java",
                "Wrote documentation",
                "Ran the release process");
    }

    @Test
    void unmatchedBulletsKeepTheirProfileOrderAmongThemselves() {
        CvProfile profile = profileWithBullets("A", "B", "C", "D");
        TailoredApplication application = applicationCiting("C");

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences().getFirst().bullets()).containsExactly("C", "A", "B", "D");
    }

    @Test
    void severalMatchedBulletsKeepTheirProfileOrderAmongThemselves() {
        CvProfile profile = profileWithBullets("A", "B", "C", "D");
        TailoredApplication application = applicationCiting("D", "B");

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences().getFirst().bullets()).containsExactly("B", "D", "A", "C");
    }

    @Test
    void bulletTextIsCarriedThroughByteForByte() {
        // The renderer adds layout, never content. A bullet on the page must be the bullet
        // in the database — this is Slice 2b's guarantee re-asserted at the render layer.
        CvProfile profile = profileWithBullets("Reduced p99 latency by 40% — measured & verified");
        TailoredApplication application = applicationCiting();

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences().getFirst().bullets())
                .containsExactly("Reduced p99 latency by 40% — measured & verified");
    }

    @Test
    void anExperienceWithNoMatchesStillAppears() {
        CvProfile profile = profileWithBullets("A", "B");
        TailoredApplication application = applicationCiting();

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences()).hasSize(1);
        assertThat(cv.experiences().getFirst().bullets()).containsExactly("A", "B");
    }

    @Test
    void theLetterCarriesTheProseTheCandidateWrote() {
        CvProfile profile = profileWithBullets("A");
        TailoredApplication application = applicationCiting();
        application.setLetterProse("Hej,\n\nJag söker tjänsten som plattformsingenjör.");

        LetterContent letter = document.letter(application, profile,
                LocalDate.parse("2026-08-31"));

        assertThat(letter.body()).isEqualTo("Hej,\n\nJag söker tjänsten som plattformsingenjör.");
        assertThat(letter.candidateName()).isEqualTo("Cai Wain");
        assertThat(letter.employerName()).isEqualTo("Example AB");
        assertThat(letter.jobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(letter.date()).isEqualTo(LocalDate.parse("2026-08-31"));
    }

    @Test
    void anEmptyLetterBodyIsAllowed() {
        // Spec section 3.4: approval is the candidate's judgement, and a form that wants only
        // a CV is common. The letter still renders.
        CvProfile profile = profileWithBullets("A");
        TailoredApplication application = applicationCiting();
        application.setLetterProse(null);

        LetterContent letter = document.letter(application, profile,
                LocalDate.parse("2026-08-31"));

        assertThat(letter.body()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ApplicationDocumentTest
```

Expected: compilation failure — the render classes do not exist.

- [ ] **Step 3: Write the content records**

Create `backend/src/main/java/se/caiowain/jobseeker/render/CvContent.java`:

```java
package se.caiowain.jobseeker.render;

import java.util.List;

/**
 * A CV as a reader sees it. Named {@code CvContent} rather than {@code CvDocument} because
 * {@code profile.domain.CvDocument} is already the uploaded PDF from Slice 2a.
 */
public record CvContent(String fullName, String headline, String email, String phone,
                        String location, String summary, List<String> skills,
                        List<Experience> experiences) {

    public record Experience(String employer, String title, String startDate, String endDate,
                             String location, List<String> bullets) {
    }
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/render/LetterContent.java`:

```java
package se.caiowain.jobseeker.render;

import java.time.LocalDate;

/**
 * @param body the prose the candidate wrote. Empty is legitimate — see the spec's note on an
 *             empty letter body; the letter still renders and is still archived.
 */
public record LetterContent(String candidateName, String candidateEmail, String candidatePhone,
                            String candidateLocation, String employerName, String jobTitle,
                            LocalDate date, String body) {
}
```

- [ ] **Step 4: Write `ApplicationDocument`**

Create `backend/src/main/java/se/caiowain/jobseeker/render/ApplicationDocument.java`:

```java
package se.caiowain.jobseeker.render;

import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.CvSkill;
import se.caiowain.jobseeker.tailor.domain.ApplicationEvidence;
import se.caiowain.jobseeker.tailor.domain.ApplicationRequirement;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stored rows to what a reader sees. Pure: no Spring, no I/O, no HTML.
 *
 * <p>The CV keeps <b>every</b> experience and <b>every</b> bullet, ordering the ones the model
 * matched to a requirement ahead of the rest within each job. Filtering to matched bullets
 * alone would drop real work history an employer expects to see, and the ranking the model
 * produced is not persisted — {@code ApplicationAssembler} receives {@code rankedBulletIds}
 * and discards them. Ordering expresses relevance without hiding anything.
 */
public class ApplicationDocument {

    public CvContent cv(TailoredApplication application, CvProfile profile) {
        Matched matched = matchedBullets(application);

        List<CvContent.Experience> experiences = new ArrayList<>();
        for (CvExperience experience : profile.getExperiences()) {
            List<String> first = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            for (CvExperienceBullet bullet : experience.getBullets()) {
                (matched.contains(bullet) ? first : rest).add(bullet.getText());
            }
            first.addAll(rest);

            experiences.add(new CvContent.Experience(
                    experience.getEmployer(), experience.getTitle(),
                    experience.getStartDate(), experience.getEndDate(),
                    experience.getLocation(), List.copyOf(first)));
        }

        List<String> skills = profile.getSkills().stream().map(CvSkill::getName).toList();

        return new CvContent(profile.getFullName(), profile.getHeadline(), profile.getEmail(),
                profile.getPhone(), profile.getLocation(), profile.getSummary(),
                skills, List.copyOf(experiences));
    }

    public LetterContent letter(TailoredApplication application, CvProfile profile, LocalDate date) {
        String body = application.getLetterProse() == null ? "" : application.getLetterProse();
        return new LetterContent(
                profile.getFullName(), profile.getEmail(), profile.getPhone(),
                profile.getLocation(),
                application.getJobPosting().getEmployerName(),
                application.getJobPosting().getTitle(),
                date, body);
    }

    /**
     * Which bullets the model cited. Matched by id where the evidence still carries one, and
     * by exact text otherwise — the foreign key is nullable by design in Slice 2b, so the
     * snapshot is the fallback.
     */
    private static Matched matchedBullets(TailoredApplication application) {
        Set<Long> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (ApplicationRequirement requirement : application.getRequirements()) {
            for (ApplicationEvidence evidence : requirement.getEvidence()) {
                if (evidence.getCvExperienceBulletId() != null) {
                    ids.add(evidence.getCvExperienceBulletId());
                }
                if (evidence.getBulletText() != null) {
                    texts.add(evidence.getBulletText());
                }
            }
        }
        return new Matched(ids, texts);
    }

    private record Matched(Set<Long> ids, Set<String> texts) {
        boolean contains(CvExperienceBullet bullet) {
            return (bullet.getId() != null && ids.contains(bullet.getId()))
                    || texts.contains(bullet.getText());
        }
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ApplicationDocumentTest
```

Expected: PASS, all seven tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/render backend/src/test/java/se/caiowain/jobseeker/render
git commit -m "feat: derive CV and letter content from an approved application"
```

---

## Task 4: The HTML builder

The single source of layout. The preview endpoint returns exactly this string and the renderer prints exactly this string — that shared call is the whole no-drift guarantee.

**Files:**
- Create: `render/DocumentHtmlBuilder.java`
- Test: `src/test/java/se/caiowain/jobseeker/render/DocumentHtmlBuilderTest.java`

**Interfaces:**
- Consumes: `CvContent`, `LetterContent` (Task 3).
- Produces: `DocumentHtmlBuilder.cvHtml(CvContent) -> String` and `DocumentHtmlBuilder.letterHtml(LetterContent) -> String`, each a complete standalone HTML document.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/render/DocumentHtmlBuilderTest.java`:

```java
package se.caiowain.jobseeker.render;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentHtmlBuilderTest {

    private final DocumentHtmlBuilder builder = new DocumentHtmlBuilder();

    private CvContent cv(String... bullets) {
        return new CvContent("Cai Wain", "Senior Software Engineer", "cai@example.com",
                "+46 70 000 00 00", "Stockholm, Sweden", "Backend engineer.",
                List.of("Java", "Spring Boot"),
                List.of(new CvContent.Experience("Acme AB", "Backend Developer",
                        "2022", "2026", "Stockholm", List.of(bullets))));
    }

    @Test
    void producesAStandaloneDocument() {
        String html = builder.cvHtml(cv("Built REST APIs"));

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<style>");
        assertThat(html).contains("@page");
    }

    @Test
    void referencesNoExternalResource() {
        // A font or stylesheet fetched at render time makes the output non-deterministic and
        // breaks offline. It would also fail silently — the document simply looks different.
        String html = builder.cvHtml(cv("Built REST APIs"));

        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(html).doesNotContain("<link").doesNotContain("<script").doesNotContain("@import");
    }

    @Test
    void carriesBulletTextThrough() {
        String html = builder.cvHtml(cv("Built REST APIs in Java"));

        assertThat(html).contains("Built REST APIs in Java");
    }

    @Test
    void escapesMarkupSoAContentBulletCannotBreakTheDocument() {
        // A real CV says things like "R&D" and "latency < 50ms". Unescaped, they corrupt the
        // page; worse, a "<" could swallow the rest of the document silently.
        String html = builder.cvHtml(cv("R&D on <adaptive> latency < 50ms"));

        assertThat(html).contains("R&amp;D on &lt;adaptive&gt; latency &lt; 50ms");
        assertThat(html).doesNotContain("<adaptive>");
    }

    @Test
    void keepsSwedishCharacters() {
        String html = builder.cvHtml(cv("Ansvarade för plattformens tillgänglighet"));

        assertThat(html).contains("Ansvarade för plattformens tillgänglighet");
        assertThat(html).contains("charset=\"utf-8\"");
    }

    @Test
    void rendersBulletsInTheOrderGiven() {
        String html = builder.cvHtml(cv("First one", "Second one", "Third one"));

        assertThat(html.indexOf("First one")).isLessThan(html.indexOf("Second one"));
        assertThat(html.indexOf("Second one")).isLessThan(html.indexOf("Third one"));
    }

    @Test
    void theLetterCarriesTheProseAndPreservesItsParagraphs() {
        LetterContent letter = new LetterContent("Cai Wain", "cai@example.com", null,
                "Stockholm", "Example AB", "Plattformsingenjör",
                LocalDate.parse("2026-08-31"), "Hej,\n\nJag söker tjänsten.");

        String html = builder.letterHtml(letter);

        assertThat(html).contains("Example AB").contains("Plattformsingenjör");
        assertThat(html).contains("Hej,").contains("Jag söker tjänsten.");
        // Two paragraphs, not one run-on line.
        assertThat(html).contains("<p>");
    }

    @Test
    void anEmptyLetterBodyStillProducesADocument() {
        LetterContent letter = new LetterContent("Cai Wain", "cai@example.com", null,
                "Stockholm", "Example AB", "Utvecklare",
                LocalDate.parse("2026-08-31"), "");

        String html = builder.letterHtml(letter);

        assertThat(html).startsWith("<!DOCTYPE html>").contains("Cai Wain");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=DocumentHtmlBuilderTest
```

Expected: compilation failure — `DocumentHtmlBuilder` does not exist.

- [ ] **Step 3: Write `DocumentHtmlBuilder`**

Create `backend/src/main/java/se/caiowain/jobseeker/render/DocumentHtmlBuilder.java`:

```java
package se.caiowain.jobseeker.render;

import java.util.List;

/**
 * The single source of layout. Pure: no Spring, no I/O.
 *
 * <p>The preview endpoint returns exactly this string and {@link PdfRenderer} prints exactly
 * this string. That shared call is what makes the preview faithful — a design with two
 * renderers could only agree by coincidence, and would disagree silently.
 *
 * <p>Nothing here is fetched at render time: fonts are a system stack and the CSS is inline.
 * An external resource would make the output depend on the network and change the document
 * without any error when it failed to load.
 */
public class DocumentHtmlBuilder {

    private static final String FONT_STACK = "Georgia, 'Times New Roman', Times, serif";

    public String cvHtml(CvContent cv) {
        StringBuilder body = new StringBuilder();

        body.append("<header><h1>").append(escape(cv.fullName())).append("</h1>");
        if (notBlank(cv.headline())) {
            body.append("<p class=\"headline\">").append(escape(cv.headline())).append("</p>");
        }
        body.append("<p class=\"contact\">")
                .append(joinEscaped(cv.email(), cv.phone(), cv.location()))
                .append("</p></header>");

        if (notBlank(cv.summary())) {
            body.append("<section><h2>Profile</h2><p>")
                    .append(escape(cv.summary())).append("</p></section>");
        }

        if (!cv.skills().isEmpty()) {
            body.append("<section><h2>Skills</h2><p class=\"skills\">");
            for (int i = 0; i < cv.skills().size(); i++) {
                if (i > 0) {
                    body.append(" &middot; ");
                }
                body.append(escape(cv.skills().get(i)));
            }
            body.append("</p></section>");
        }

        body.append("<section><h2>Experience</h2>");
        for (CvContent.Experience experience : cv.experiences()) {
            body.append("<article><h3>").append(escape(experience.title()))
                    .append(" &mdash; ").append(escape(experience.employer())).append("</h3>")
                    .append("<p class=\"dates\">")
                    .append(joinEscaped(dates(experience), experience.location()))
                    .append("</p><ul>");
            for (String bullet : experience.bullets()) {
                body.append("<li>").append(escape(bullet)).append("</li>");
            }
            body.append("</ul></article>");
        }
        body.append("</section>");

        return page("CV — " + safe(cv.fullName()), body.toString());
    }

    public String letterHtml(LetterContent letter) {
        StringBuilder body = new StringBuilder();

        body.append("<header><h1>").append(escape(letter.candidateName())).append("</h1>")
                .append("<p class=\"contact\">")
                .append(joinEscaped(letter.candidateEmail(), letter.candidatePhone(),
                        letter.candidateLocation()))
                .append("</p></header>");

        body.append("<p class=\"meta\">").append(escape(letter.date().toString()));
        if (notBlank(letter.employerName())) {
            body.append("<br>").append(escape(letter.employerName()));
        }
        if (notBlank(letter.jobTitle())) {
            body.append("<br>").append(escape(letter.jobTitle()));
        }
        body.append("</p>");

        body.append("<section class=\"body\">").append(paragraphs(letter.body())).append("</section>");
        body.append("<p class=\"signoff\">").append(escape(letter.candidateName())).append("</p>");

        return page("Cover letter — " + safe(letter.candidateName()), body.toString());
    }

    /** Blank-line separated text becomes paragraphs; a single newline becomes a break. */
    private static String paragraphs(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.strip().split("\\n\\s*\\n")) {
            out.append("<p>").append(escape(paragraph).replace("\n", "<br>")).append("</p>");
        }
        return out.toString();
    }

    private static String dates(CvContent.Experience experience) {
        if (!notBlank(experience.startDate()) && !notBlank(experience.endDate())) {
            return null;
        }
        return safe(experience.startDate()) + " – " + safe(experience.endDate());
    }

    private static String joinEscaped(String... parts) {
        List<String> present = java.util.Arrays.stream(parts)
                .filter(DocumentHtmlBuilder::notBlank)
                .map(DocumentHtmlBuilder::escape)
                .toList();
        return String.join(" &middot; ", present);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Content comes from a CV and a job ad. Both routinely contain &amp;, &lt; and quotes. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                <style>
                @page { size: A4; margin: 18mm 16mm; }
                * { box-sizing: border-box; }
                body { font-family: %s; font-size: 10.5pt; line-height: 1.45;
                       color: #111; margin: 0; }
                h1 { font-size: 20pt; margin: 0 0 2mm; letter-spacing: 0.2px; }
                h2 { font-size: 9pt; text-transform: uppercase; letter-spacing: 1.2px;
                     color: #555; border-bottom: 0.4pt solid #bbb;
                     padding-bottom: 1.5mm; margin: 7mm 0 3mm; }
                h3 { font-size: 11pt; margin: 0 0 1mm; }
                p { margin: 0 0 2.5mm; }
                .headline { font-size: 11.5pt; color: #333; }
                .contact, .dates, .meta { font-size: 9.5pt; color: #555; }
                .skills { font-size: 10pt; }
                article { margin-bottom: 5mm; page-break-inside: avoid; }
                ul { margin: 1.5mm 0 0; padding-left: 5mm; }
                li { margin-bottom: 1.2mm; }
                .body p { margin-bottom: 3.5mm; }
                .signoff { margin-top: 8mm; }
                </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(escape(title), FONT_STACK, body);
    }
}
```

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=DocumentHtmlBuilderTest
```

Expected: PASS, all eight tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/render/DocumentHtmlBuilder.java \
        backend/src/test/java/se/caiowain/jobseeker/render/DocumentHtmlBuilderTest.java
git commit -m "feat: build the one HTML document that preview and renderer share"
```

---

## Task 5: The PDF renderer

The only component in the project that drives a browser.

**Files:**
- Modify: `backend/pom.xml`
- Create: `render/PdfRenderer.java`, `render/RendererUnavailableException.java`, `render/RenderConfig.java`
- Test: `src/test/java/se/caiowain/jobseeker/render/PdfRendererTest.java`

**Interfaces:**
- Consumes: `DocumentHtmlBuilder` (Task 4).
- Produces: `PdfRenderer.render(String html) -> byte[]`, `PdfRenderer.isAvailable() -> boolean`, `PdfRenderer.rendererName() -> String`; `RendererUnavailableException extends RuntimeException`; beans `applicationDocument()`, `documentHtmlBuilder()`.

- [ ] **Step 1: Add the dependency**

In `backend/pom.xml`, alongside the existing dependencies:

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version><!-- resolve this: see the command below --></version>
</dependency>
```

**This is the one value in the plan that cannot be written in advance** — it must be resolved
against the repository at implementation time, exactly as Slice 2b pinned Spring AI after
verifying it against the jars rather than guessing. **Resolve and pin an exact version before
continuing — never a range, never a property. Report the version you pinned.** The project pins Spring AI and PDFBox exactly for the same reason. Verify the version you choose actually resolves:

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q dependency:get -Dartifact=com.microsoft.playwright:playwright:<version>
ls ~/.m2/repository/com/microsoft/playwright/playwright/
```

If it does not resolve, pick the newest version that does and record which one in your report. This is the one step in the plan whose exact value cannot be written in advance.

- [ ] **Step 2: Install Chromium once, outside the build**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

This downloads a browser. It is a **developer prerequisite, like Ollama** — never part of the build or the test run, because the suite may not reach the network. If it fails, note it and continue: the tests in this task skip cleanly without a browser, and the rest of the plan does not depend on one.

- [ ] **Step 3: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/render/PdfRendererTest.java`:

```java
package se.caiowain.jobseeker.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads the rendered PDF back with PDFBox — already a dependency from Slice 2a — so these
 * tests prove the file says what the screen said, rather than proving a file was produced.
 *
 * <p>Skipped when no browser is installed. A Playwright browser download is network access,
 * which the suite forbids, so Chromium is a developer prerequisite exactly as Ollama is.
 */
class PdfRendererTest {

    private final PdfRenderer renderer = new PdfRenderer();
    private final DocumentHtmlBuilder builder = new DocumentHtmlBuilder();

    @BeforeEach
    void requireABrowser() {
        assumeTrue(renderer.isAvailable(),
                "No Chromium installed — run the Playwright install step to exercise this test");
    }

    private CvContent cv(String... bullets) {
        return new CvContent("Cai Wain", "Senior Software Engineer", "cai@example.com",
                null, "Stockholm, Sweden", null, List.of("Java"),
                List.of(new CvContent.Experience("Acme AB", "Backend Developer",
                        "2022", "2026", "Stockholm", List.of(bullets))));
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void producesSomethingPdfBoxCanOpen() throws Exception {
        byte[] pdf = renderer.render(builder.cvHtml(cv("Built REST APIs in Java")));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF-");
    }

    @Test
    void theBulletsSurviveIntoTheFile() throws Exception {
        // The fidelity check: what the screen showed must be extractable from the artifact.
        byte[] pdf = renderer.render(builder.cvHtml(cv("Built REST APIs in Java")));

        assertThat(textOf(pdf)).contains("Built REST APIs in Java").contains("Cai Wain");
    }

    @Test
    void swedishCharactersSurviveTheRoundTrip() throws Exception {
        // Silent font-embedding failure is the classic PDF defect, and Slice 2b named
        // embedded fonts for åäö as a requirement.
        byte[] pdf = renderer.render(
                builder.cvHtml(cv("Ansvarade för plattformens tillgänglighet i Göteborg")));

        assertThat(textOf(pdf)).contains("Ansvarade för plattformens tillgänglighet i Göteborg");
    }

    @Test
    void namesTheEngineThatProducedTheFile() {
        assertThat(renderer.rendererName()).isNotBlank().containsIgnoringCase("chromium");
    }
}
```

- [ ] **Step 4: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PdfRendererTest
```

Expected: compilation failure — `PdfRenderer` does not exist. (If you have no browser installed, the tests will later *skip* rather than pass; that is the intended behaviour, not a failure.)

- [ ] **Step 5: Write the exception and the renderer**

Create `backend/src/main/java/se/caiowain/jobseeker/render/RendererUnavailableException.java`:

```java
package se.caiowain.jobseeker.render;

/** No browser to render with. Maps to HTTP 503, like an unreachable Ollama. */
public class RendererUnavailableException extends RuntimeException {
    public RendererUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/render/PdfRenderer.java`:

```java
package se.caiowain.jobseeker.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

/**
 * HTML to PDF through headless Chromium. The only component in the project that drives a
 * browser, and the reason the preview can be trusted: it prints the same string the preview
 * endpoint returns.
 *
 * <p>A Playwright instance is created per render rather than held open. Approving one
 * application is a deliberate, occasional act that already takes seconds, and a per-call
 * instance avoids owning a browser process's lifecycle for the life of the application.
 */
@Component
public class PdfRenderer {

    public byte[] render(String html) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {

            Page page = browser.newPage();
            // No external resources by construction, so LOAD settles immediately.
            page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
            return page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true));

        } catch (Exception e) {
            throw new RendererUnavailableException(
                    "Could not render the document. Install a browser with: ./mvnw exec:java "
                            + "-Dexec.mainClass=com.microsoft.playwright.CLI "
                            + "-Dexec.args=\"install chromium\" (" + e.getMessage() + ")", e);
        }
    }

    public boolean isAvailable() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            return browser.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** Recorded on every archive row, so a future reader knows what produced the file. */
    public String rendererName() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            return "chromium/" + browser.version();
        } catch (Exception e) {
            return "chromium/unavailable";
        }
    }
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/render/RenderConfig.java`:

```java
package se.caiowain.jobseeker.render;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kept free of Spring annotations so their tests stay pure — as TailoringConfig and FitConfig do. */
@Configuration
public class RenderConfig {

    @Bean public ApplicationDocument applicationDocument() { return new ApplicationDocument(); }

    @Bean public DocumentHtmlBuilder documentHtmlBuilder() { return new DocumentHtmlBuilder(); }
}
```

- [ ] **Step 6: Run it — expect PASS (or SKIP without a browser)**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PdfRendererTest
```

Expected: four passing tests with Chromium installed; four **skipped** without it. Report which you saw — a skip here is a legitimate result, and saying "passed" when they skipped would misstate what was proved.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
cd ..
git add backend/pom.xml backend/src
git commit -m "feat: render documents to PDF through headless Chromium"
```

---

## Task 6: Approval renders, hashes and freezes

Approval stops being a status flip and becomes the moment the documents become real.

**Files:**
- Create: `archive/ArchiveService.java`, `archive/ApplicationNotDraftException.java`
- Modify: `tailor/TailoringService.java` (remove `approve`), `tailor/api/TailoringController.java` (remove the `approve` mapping)
- Test: `src/test/java/se/caiowain/jobseeker/archive/ArchiveServiceTest.java`

**Interfaces:**
- Consumes: `ApplicationDocument`, `DocumentHtmlBuilder`, `PdfRenderer` (Tasks 3–5); `ApplicationArchiveRepository` (Task 2); `TailoredApplicationRepository`, `TailoredApplication`, `ApplicationStatus` (Slice 2b).
- Produces: `ArchiveService.cvHtml(Long applicationId) -> String`, `ArchiveService.letterHtml(Long applicationId) -> String`, `ArchiveService.approve(Long applicationId) -> ApplicationArchive`; `ApplicationNotDraftException extends RuntimeException`.

**The guarantee is structural here.** `approve` renders by calling its own `cvHtml` and `letterHtml`. The preview endpoint in Task 7 calls the same two methods. There is no second path that could produce different HTML.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/archive/ArchiveServiceTest.java`:

```java
package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.render.PdfRenderer;
import se.caiowain.jobseeker.render.RendererUnavailableException;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.*;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The renderer is mocked so the archive's own logic is testable without a browser — the same
 * arrangement Slices 2b and 2d use for the Ollama client. Real rendering is covered by
 * PdfRendererTest, which skips when Chromium is absent.
 */
@SpringBootTest
class ArchiveServiceTest extends AbstractIntegrationTest {

    @Autowired ArchiveService archiveService;
    @Autowired ApplicationArchiveRepository archives;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean PdfRenderer renderer;

    private Long applicationId;

    @BeforeEach
    void seed() {
        archives.deleteAllInBatch();
        applications.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        CvDocument document = new CvDocument();
        document.setFilename("cv.pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(4);
        document.setSha256("f".repeat(64));
        document.setContent("%PDF".getBytes(StandardCharsets.UTF_8));
        document.setUploadedAt(Instant.parse("2026-08-31T08:00:00Z"));
        documents.save(document);

        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setFullName("Cai Wain");
        profile.setStatus(ProfileStatus.READY);
        CvExperience experience = new CvExperience();
        experience.setEmployer("Acme AB");
        experience.setTitle("Backend Developer");
        experience.setOrdinal(0);
        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built REST APIs in Java");
        bullet.setOrdinal(0);
        experience.addBullet(bullet);
        profile.addExperience(experience);
        profiles.saveAndFlush(profile);

        JobPosting job = new JobPosting();
        job.setFingerprint("arch-1");
        job.setCanonicalUrl("https://example.test/jobs/arch-1");
        job.setTitle("Plattformsingenjör");
        job.setEmployerName("Example AB");
        job.setDescription("Vi söker en utvecklare med erfarenhet av Java.");
        job.setApplyUrl("https://example.test/apply/arch-1");
        job.setFirstSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setLastSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        jobs.save(job);

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);
        application.setCvProfile(profile);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setCoveragePercent(100);
        application.setGeneratedAt(Instant.parse("2026-08-31T09:00:00Z"));
        application.setLetterProse("Hej,\n\nJag söker tjänsten.");
        ApplicationRequirement requirement = new ApplicationRequirement();
        requirement.setText("erfarenhet av Java");
        requirement.setOrdinal(0);
        ApplicationEvidence evidence = new ApplicationEvidence();
        evidence.setBulletText("Built REST APIs in Java");
        evidence.setOrdinal(0);
        requirement.addEvidence(evidence);
        application.addRequirement(requirement);
        applicationId = applications.saveAndFlush(application).getId();

        when(renderer.rendererName()).thenReturn("chromium/mocked");
        doReturn("%PDF-cv".getBytes(StandardCharsets.UTF_8))
                .when(renderer).render(anyString());
    }

    @Test
    void approvingFreezesTheDocumentsAndTheAd() {
        ApplicationArchive archive = archiveService.approve(applicationId);

        assertThat(archive.getJobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(archive.getEmployerName()).isEqualTo("Example AB");
        assertThat(archive.getJobDescriptionText()).contains("erfarenhet av Java");
        assertThat(archive.getLetterText()).isEqualTo("Hej,\n\nJag söker tjänsten.");
        assertThat(archive.getCvPdf()).isNotEmpty();
        assertThat(archive.getLetterPdf()).isNotEmpty();
        assertThat(archive.getRenderedBy()).isEqualTo("chromium/mocked");
        assertThat(archive.getApprovedAt()).isNotNull();
    }

    @Test
    void approvingHashesEachFile() {
        ApplicationArchive archive = archiveService.approve(applicationId);

        // SHA-256 of "%PDF-cv" — the point is that the digest is of the stored bytes, so an
        // archive row can prove which file it holds rather than merely describing one.
        assertThat(archive.getCvPdfSha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(archive.getLetterPdfSha256()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void approvingFlipsTheApplicationToApproved() {
        archiveService.approve(applicationId);

        TailoredApplication reloaded = applications.findById(applicationId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(reloaded.getReviewedAt()).isNotNull();
    }

    @Test
    void approvingSomethingAlreadyApprovedIsRejected() {
        archiveService.approve(applicationId);

        assertThatThrownBy(() -> archiveService.approve(applicationId))
                .isInstanceOf(ApplicationNotDraftException.class);

        assertThat(archives.count()).isEqualTo(1);
    }

    @Test
    void theArchiveDoesNotMoveWhenTheProfileChangesAfterwards() {
        ApplicationArchive archive = archiveService.approve(applicationId);
        byte[] frozen = archive.getCvPdf();

        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setFullName("Someone Else Entirely");
        profiles.saveAndFlush(profile);

        assertThat(archives.findById(archive.getId()).orElseThrow().getCvPdf()).isEqualTo(frozen);
    }

    @Test
    void theArchiveSurvivesTheApplicationBeingDeleted() {
        // Slice 2b replaces a DRAFT or DISCARDED application outright on re-tailor. Every
        // approval must still survive that — it is the entire reason the table exists.
        ApplicationArchive archive = archiveService.approve(applicationId);
        Long archiveId = archive.getId();

        applications.deleteById(applicationId);
        applications.flush();

        ApplicationArchive survivor = archives.findById(archiveId).orElseThrow();
        assertThat(survivor.getTailoredApplication()).isNull();
        assertThat(survivor.getJobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(survivor.getCvPdf()).isNotEmpty();
    }

    @Test
    void aRenderFailurePersistsNothing() {
        doThrow(new RendererUnavailableException("no browser", new IllegalStateException()))
                .when(renderer).render(anyString());

        assertThatThrownBy(() -> archiveService.approve(applicationId))
                .isInstanceOf(RendererUnavailableException.class);

        assertThat(archives.count()).isZero();
        assertThat(applications.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.DRAFT);
    }

    @Test
    void thePreviewAndTheApprovedDocumentComeFromTheSameHtml() {
        // The no-drift guarantee, asserted rather than asserted-about: approve() renders by
        // calling these same two methods.
        String cvHtml = archiveService.cvHtml(applicationId);
        String letterHtml = archiveService.letterHtml(applicationId);

        assertThat(cvHtml).contains("Built REST APIs in Java").contains("Cai Wain");
        assertThat(letterHtml).contains("Jag söker tjänsten.");
        assertThat(archiveService.cvHtml(applicationId)).isEqualTo(cvHtml);
    }

    @Test
    void anUnknownApplicationIsRejected() {
        assertThatThrownBy(() -> archiveService.approve(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ArchiveServiceTest
```

Expected: compilation failure — `ArchiveService` does not exist.

- [ ] **Step 3: Write the exception**

Create `backend/src/main/java/se/caiowain/jobseeker/archive/ApplicationNotDraftException.java`:

```java
package se.caiowain.jobseeker.archive;

/**
 * Approval is the freeze point, so it may happen exactly once. Slice 2b's {@code approve}
 * accepted any status and simply set a flag; approving twice now would render and archive a
 * second time. Maps to HTTP 409.
 */
public class ApplicationNotDraftException extends RuntimeException {
    public ApplicationNotDraftException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write `ArchiveService`**

Create `backend/src/main/java/se/caiowain/jobseeker/archive/ArchiveService.java`:

```java
package se.caiowain.jobseeker.archive;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.render.ApplicationDocument;
import se.caiowain.jobseeker.render.DocumentHtmlBuilder;
import se.caiowain.jobseeker.render.PdfRenderer;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Approval: render, hash, freeze.
 *
 * <p>{@link #approve} renders by calling {@link #cvHtml} and {@link #letterHtml}, and the
 * preview endpoint calls those same two methods. That shared call is the whole reason the
 * preview can be trusted — there is no second path that could produce different HTML.
 *
 * <p>No {@code noRollbackFor}: nothing is persisted unless both documents render. A failed
 * approval must leave the application in {@code DRAFT} with no archive row, so a missing
 * browser can never be mistaken for an approved application.
 */
@Service
public class ArchiveService {

    private final ApplicationArchiveRepository archives;
    private final TailoredApplicationRepository applications;
    private final ApplicationDocument document;
    private final DocumentHtmlBuilder html;
    private final PdfRenderer renderer;

    public ArchiveService(ApplicationArchiveRepository archives,
                          TailoredApplicationRepository applications,
                          ApplicationDocument document,
                          DocumentHtmlBuilder html,
                          PdfRenderer renderer) {
        this.archives = archives;
        this.applications = applications;
        this.document = document;
        this.html = html;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public String cvHtml(Long applicationId) {
        TailoredApplication application = require(applicationId);
        return html.cvHtml(document.cv(application, application.getCvProfile()));
    }

    @Transactional(readOnly = true)
    public String letterHtml(Long applicationId) {
        TailoredApplication application = require(applicationId);
        return html.letterHtml(document.letter(application, application.getCvProfile(), today()));
    }

    @Transactional
    public ApplicationArchive approve(Long applicationId) {
        TailoredApplication application = require(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT) {
            throw new ApplicationNotDraftException(
                    "Only a draft can be approved. This application is "
                            + application.getStatus() + ".");
        }

        CvProfile profile = application.getCvProfile();
        JobPosting job = application.getJobPosting();

        byte[] cvPdf = renderer.render(
                html.cvHtml(document.cv(application, profile)));
        byte[] letterPdf = renderer.render(
                html.letterHtml(document.letter(application, profile, today())));

        ApplicationArchive archive = new ApplicationArchive();
        archive.setTailoredApplication(application);
        archive.setJobPosting(job);
        archive.setCvProfile(profile);

        // Copied, not referenced: JobMergeService overwrites job_posting.description on
        // re-ingest and employers delete postings.
        archive.setJobTitle(job.getTitle());
        archive.setEmployerName(job.getEmployerName());
        archive.setJobCanonicalUrl(job.getCanonicalUrl());
        archive.setJobApplyUrl(job.getApplyUrl());
        archive.setJobDescriptionText(job.getDescription());

        archive.setLetterText(application.getLetterProse());
        archive.setCvPdf(cvPdf);
        archive.setCvPdfSha256(sha256(cvPdf));
        archive.setLetterPdf(letterPdf);
        archive.setLetterPdfSha256(sha256(letterPdf));
        archive.setCoveragePercent(application.getCoveragePercent());
        archive.setRenderedBy(renderer.rendererName());
        archive.setApprovedAt(Instant.now());

        ApplicationArchive saved = archives.save(archive);

        application.setStatus(ApplicationStatus.APPROVED);
        application.setReviewedAt(Instant.now());
        applications.save(application);

        return saved;
    }

    private TailoredApplication require(Long id) {
        return applications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No application with id " + id));
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
```

- [ ] **Step 5: Remove `approve` from the tailoring slice**

In `backend/src/main/java/se/caiowain/jobseeker/tailor/TailoringService.java`, delete the `approve` method entirely — `ArchiveService.approve` replaces it, and leaving a second way to reach `APPROVED` would let an application be approved without ever being rendered or archived.

In `backend/src/main/java/se/caiowain/jobseeker/tailor/api/TailoringController.java`, delete this mapping — Task 7 re-adds the same path in `ArchiveController`:

```java
    @PostMapping("/api/applications/{id}/approve")
    public ApplicationDto approve(@PathVariable Long id) {
        return toDto(service.approve(id));
    }
```

Then check whether any test referenced the removed method:

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
grep -rn 'service.approve\|\.approve(' src/test/java | grep -i tailor
```

Update any hit to call `ArchiveService` instead, or delete the assertion if it duplicated one now covered by `ArchiveServiceTest`. Say in your report which tests you touched and why.

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ArchiveServiceTest
```

Expected: PASS, all nine tests.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
cd ..
git add backend/src
git commit -m "feat: approval renders, hashes and freezes the application"
```

---

## Task 7: Preview and archive endpoints

**Files:**
- Create: `archive/api/ArchiveController.java`, `archive/api/dto/ArchiveSummaryDto.java`, `archive/api/dto/ArchiveDetailDto.java`
- Modify: `api/ApiExceptionHandler.java`
- Test: `src/test/java/se/caiowain/jobseeker/archive/api/ArchiveApiTest.java`

**Interfaces:**
- Consumes: `ArchiveService` (Task 6), `ApplicationArchiveRepository` (Task 2).
- Produces: `GET /api/applications/{id}/preview/cv`, `GET /api/applications/{id}/preview/letter`, `POST /api/applications/{id}/approve`, `GET /api/archive`, `GET /api/archive/{id}`, `GET /api/archive/{id}/cv.pdf`, `GET /api/archive/{id}/letter.pdf`.

- [ ] **Step 1: Write the DTOs**

Create `backend/src/main/java/se/caiowain/jobseeker/archive/api/dto/ArchiveSummaryDto.java`:

```java
package se.caiowain.jobseeker.archive.api.dto;

import java.time.Instant;

public record ArchiveSummaryDto(Long id, String jobTitle, String employerName,
                                int coveragePercent, Instant approvedAt) {
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/archive/api/dto/ArchiveDetailDto.java`:

```java
package se.caiowain.jobseeker.archive.api.dto;

import java.time.Instant;

/**
 * @param jobApplyUrl shown so the candidate can find the posting again. Nothing dereferences it.
 * @param cvPdfSha256 lets the candidate assert which file was sent, not merely describe it.
 */
public record ArchiveDetailDto(Long id, String jobTitle, String employerName,
                               String jobCanonicalUrl, String jobApplyUrl,
                               String jobDescriptionText, String letterText,
                               int coveragePercent, String renderedBy, Instant approvedAt,
                               String cvPdfSha256, String letterPdfSha256) {
}
```

- [ ] **Step 2: Write the failing controller test**

Create `backend/src/test/java/se/caiowain/jobseeker/archive/api/ArchiveApiTest.java`:

```java
package se.caiowain.jobseeker.archive.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.render.PdfRenderer;
import se.caiowain.jobseeker.render.RendererUnavailableException;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.*;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ArchiveApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationArchiveRepository archives;
    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean PdfRenderer renderer;

    private Long applicationId;

    @BeforeEach
    void seed() {
        archives.deleteAllInBatch();
        applications.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        CvDocument document = new CvDocument();
        document.setFilename("cv.pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(4);
        document.setSha256("e".repeat(64));
        document.setContent("%PDF".getBytes(StandardCharsets.UTF_8));
        document.setUploadedAt(Instant.parse("2026-08-31T08:00:00Z"));
        documents.save(document);

        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setFullName("Cai Wain");
        profile.setStatus(ProfileStatus.READY);
        CvExperience experience = new CvExperience();
        experience.setEmployer("Acme AB");
        experience.setTitle("Backend Developer");
        experience.setOrdinal(0);
        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built REST APIs in Java");
        bullet.setOrdinal(0);
        experience.addBullet(bullet);
        profile.addExperience(experience);
        profiles.saveAndFlush(profile);

        JobPosting job = new JobPosting();
        job.setFingerprint("api-arch-1");
        job.setCanonicalUrl("https://example.test/jobs/api-arch-1");
        job.setTitle("Plattformsingenjör");
        job.setEmployerName("Example AB");
        job.setDescription("Vi söker en utvecklare med erfarenhet av Java.");
        job.setFirstSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setLastSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        jobs.save(job);

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);
        application.setCvProfile(profile);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setCoveragePercent(100);
        application.setGeneratedAt(Instant.parse("2026-08-31T09:00:00Z"));
        application.setLetterProse("Hej,\n\nJag söker tjänsten.");
        applicationId = applications.saveAndFlush(application).getId();

        when(renderer.rendererName()).thenReturn("chromium/mocked");
        doReturn("%PDF-bytes".getBytes(StandardCharsets.UTF_8)).when(renderer).render(anyString());
    }

    @Test
    void previewReturnsTheDocumentAsHtml() throws Exception {
        mvc.perform(get("/api/applications/" + applicationId + "/preview/cv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Built REST APIs in Java")));
    }

    @Test
    void previewReturnsTheLetterToo() throws Exception {
        mvc.perform(get("/api/applications/" + applicationId + "/preview/letter"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Jag söker tjänsten.")));
    }

    @Test
    void previewForAnUnknownApplicationIsFourOhFour() throws Exception {
        mvc.perform(get("/api/applications/999999/preview/cv")).andExpect(status().isNotFound());
    }

    @Test
    void approvingArchivesAndReturnsTheRow() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Plattformsingenjör"))
                .andExpect(jsonPath("$.cvPdfSha256").isNotEmpty());
    }

    @Test
    void approvingTwiceIsFourOhNine() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));
        mvc.perform(post("/api/applications/" + applicationId + "/approve"))
                .andExpect(status().isConflict());
    }

    @Test
    void approvingWithNoBrowserIsFiveOhThree() throws Exception {
        doThrow(new RendererUnavailableException("no browser", new IllegalStateException()))
                .when(renderer).render(anyString());

        mvc.perform(post("/api/applications/" + applicationId + "/approve"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void theArchiveListsWhatWasApproved() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));

        mvc.perform(get("/api/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employerName").value("Example AB"));
    }

    @Test
    void anArchivedRowCarriesTheAdAndTheLetterText() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));
        Long archiveId = archives.findAll().getFirst().getId();

        mvc.perform(get("/api/archive/" + archiveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobDescriptionText").value(
                        org.hamcrest.Matchers.containsString("erfarenhet av Java")))
                .andExpect(jsonPath("$.letterText").value(
                        org.hamcrest.Matchers.containsString("Jag söker tjänsten.")));
    }

    @Test
    void thePdfsAreServedAsPdf() throws Exception {
        mvc.perform(post("/api/applications/" + applicationId + "/approve"));
        Long archiveId = archives.findAll().getFirst().getId();

        mvc.perform(get("/api/archive/" + archiveId + "/cv.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/api/archive/" + archiveId + "/letter.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
    }

    @Test
    void anUnknownArchiveRowIsFourOhFour() throws Exception {
        mvc.perform(get("/api/archive/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/archive/999999/cv.pdf")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ArchiveApiTest
```

Expected: FAIL — the endpoints 404 because `ArchiveController` does not exist.

- [ ] **Step 4: Write `ArchiveController`**

Create `backend/src/main/java/se/caiowain/jobseeker/archive/api/ArchiveController.java`:

```java
package se.caiowain.jobseeker.archive.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.PageDto;
import se.caiowain.jobseeker.archive.ArchiveService;
import se.caiowain.jobseeker.archive.api.dto.ArchiveDetailDto;
import se.caiowain.jobseeker.archive.api.dto.ArchiveSummaryDto;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;

/**
 * Preview, approve and read the archive. The approve mapping lives here rather than in
 * TailoringController so that {@code archive} depends on {@code tailor} and never the reverse.
 */
@RestController
public class ArchiveController {

    private final ArchiveService service;
    private final ApplicationArchiveRepository archives;

    public ArchiveController(ArchiveService service, ApplicationArchiveRepository archives) {
        this.service = service;
        this.archives = archives;
    }

    /** The exact HTML the renderer prints. Same method the approve path calls. */
    @GetMapping(value = "/api/applications/{id}/preview/cv", produces = MediaType.TEXT_HTML_VALUE)
    public String previewCv(@PathVariable Long id) {
        return service.cvHtml(id);
    }

    @GetMapping(value = "/api/applications/{id}/preview/letter", produces = MediaType.TEXT_HTML_VALUE)
    public String previewLetter(@PathVariable Long id) {
        return service.letterHtml(id);
    }

    /** Renders both documents, so it takes seconds rather than milliseconds. */
    @PostMapping("/api/applications/{id}/approve")
    public ArchiveDetailDto approve(@PathVariable Long id) {
        return toDetail(service.approve(id));
    }

    @GetMapping("/api/archive")
    public PageDto<ArchiveSummaryDto> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return PageDto.of(
                archives.findAllByOrderByApprovedAtDesc(PageRequest.of(page, Math.min(size, 100))),
                ArchiveController::toSummary);
    }

    @GetMapping("/api/archive/{id}")
    public ResponseEntity<ArchiveDetailDto> detail(@PathVariable Long id) {
        return archives.findById(id)
                .map(a -> ResponseEntity.ok(toDetail(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/archive/{id}/cv.pdf")
    public ResponseEntity<byte[]> cvPdf(@PathVariable Long id) {
        return archives.findById(id)
                .map(a -> pdf(a.getCvPdf(), "cv.pdf"))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/archive/{id}/letter.pdf")
    public ResponseEntity<byte[]> letterPdf(@PathVariable Long id) {
        return archives.findById(id)
                .map(a -> pdf(a.getLetterPdf(), "letter.pdf"))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .body(bytes);
    }

    private static ArchiveSummaryDto toSummary(ApplicationArchive a) {
        return new ArchiveSummaryDto(a.getId(), a.getJobTitle(), a.getEmployerName(),
                a.getCoveragePercent(), a.getApprovedAt());
    }

    private static ArchiveDetailDto toDetail(ApplicationArchive a) {
        return new ArchiveDetailDto(a.getId(), a.getJobTitle(), a.getEmployerName(),
                a.getJobCanonicalUrl(), a.getJobApplyUrl(), a.getJobDescriptionText(),
                a.getLetterText(), a.getCoveragePercent(), a.getRenderedBy(), a.getApprovedAt(),
                a.getCvPdfSha256(), a.getLetterPdfSha256());
    }
}
```

- [ ] **Step 5: Map the two new exceptions**

In `backend/src/main/java/se/caiowain/jobseeker/api/ApiExceptionHandler.java`, add:

```java
    @ExceptionHandler(RendererUnavailableException.class)
    ProblemDetail rendererUnavailable(RendererUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(ApplicationNotDraftException.class)
    ProblemDetail notDraft(ApplicationNotDraftException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
```

with the imports:

```java
import se.caiowain.jobseeker.archive.ApplicationNotDraftException;
import se.caiowain.jobseeker.render.RendererUnavailableException;
```

The handler already maps `IllegalArgumentException` to 404, which covers an unknown application id on the preview endpoints — no extra code needed for that case.

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=ArchiveApiTest
```

Expected: PASS, all ten tests.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
cd ..
git add backend/src
git commit -m "feat: expose document preview and the application archive over REST"
```

---

## Task 8: The preview panel, and rewiring approve

**Note the interface change:** `POST /api/applications/{id}/approve` now returns an
`ArchiveDetailDto`, not an `ApplicationDto`. `approveApplication` in `applicationClient.ts`
returns `Promise<Application>` today and every caller assumes it. Both change here.

**Files:**
- Create: `frontend/src/archiveTypes.ts`, `frontend/src/api/archiveClient.ts`, `frontend/src/components/DocumentPreview.tsx`
- Modify: `frontend/src/api/applicationClient.ts`, `frontend/src/pages/ApplicationReview.tsx`

**Interfaces:**
- Consumes: the endpoints from Task 7.
- Produces: `ArchiveSummary`, `ArchiveDetail` types; `fetchArchive`, `fetchArchiveEntry` clients; `DocumentPreview` component; `approveApplication(id) -> Promise<ArchiveDetail>`.

- [ ] **Step 1: Write the types**

Create `frontend/src/archiveTypes.ts`:

```ts
export interface ArchiveSummary {
  id: number
  jobTitle: string
  employerName: string | null
  coveragePercent: number
  approvedAt: string
}

export interface ArchiveDetail {
  id: number
  jobTitle: string
  employerName: string | null
  jobCanonicalUrl: string | null
  /** Shown so you can find the posting again. Nothing in the app follows it. */
  jobApplyUrl: string | null
  /** The ad as it read on the day you applied, not as it reads now. */
  jobDescriptionText: string | null
  letterText: string | null
  coveragePercent: number
  renderedBy: string | null
  approvedAt: string
  cvPdfSha256: string
  letterPdfSha256: string
}
```

- [ ] **Step 2: Write the archive client**

Create `frontend/src/api/archiveClient.ts`, following the established shape in `applicationClient.ts`:

```ts
import type { ArchiveDetail, ArchiveSummary } from '../archiveTypes'
import type { Page } from '../types'

async function readError(response: Response): Promise<string> {
  try {
    const body = await response.json()
    return body.detail ?? body.message ?? `${response.status} ${response.statusText}`
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<T>
}

export function fetchArchive(): Promise<Page<ArchiveSummary>> {
  return json<Page<ArchiveSummary>>('/api/archive')
}

export function fetchArchiveEntry(id: number): Promise<ArchiveDetail> {
  return json<ArchiveDetail>(`/api/archive/${id}`)
}

/** The frozen files. Served inline so the browser's own viewer opens them. */
export function cvPdfUrl(id: number): string {
  return `/api/archive/${id}/cv.pdf`
}

export function letterPdfUrl(id: number): string {
  return `/api/archive/${id}/letter.pdf`
}

/** The preview is the document itself, served as HTML for an iframe. */
export function previewUrl(applicationId: number, which: 'cv' | 'letter'): string {
  return `/api/applications/${applicationId}/preview/${which}`
}
```

- [ ] **Step 3: Change `approveApplication`'s return type**

In `frontend/src/api/applicationClient.ts`, replace the existing `approveApplication` with:

```ts
/**
 * Approval renders both documents and freezes them, so it takes seconds and returns the
 * archived record rather than the application.
 */
export function approveApplication(id: number): Promise<ArchiveDetail> {
  return json<ArchiveDetail>(`/api/applications/${id}/approve`, { method: 'POST' })
}
```

and add the import at the top:

```ts
import type { ArchiveDetail } from '../archiveTypes'
```

- [ ] **Step 4: Write `DocumentPreview`**

Create `frontend/src/components/DocumentPreview.tsx`:

```tsx
import { useState } from 'react'
import { previewUrl } from '../api/archiveClient'

interface Props {
  applicationId: number
}

/**
 * The document itself, in an iframe. React deliberately does not re-implement the layout —
 * this is the same HTML the renderer prints, so what you approve is what gets archived.
 */
export function DocumentPreview({ applicationId }: Props) {
  const [which, setWhich] = useState<'cv' | 'letter'>('cv')

  const tab = (value: 'cv' | 'letter', label: string) => (
    <button
      onClick={() => setWhich(value)}
      className={`rounded-md px-3 py-1.5 text-sm transition ${
        which === value
          ? 'bg-slate-900 text-white'
          : 'border border-slate-300 hover:bg-slate-50'
      }`}
    >
      {label}
    </button>
  )

  return (
    <section className="mb-8">
      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
        Preview
      </h2>
      <p className="mb-3 text-xs text-slate-500">
        This is the document itself, not a summary of it — the same page the PDF is printed from.
      </p>
      <div className="mb-3 flex gap-2">
        {tab('cv', 'CV')}
        {tab('letter', 'Cover letter')}
      </div>
      <iframe
        key={which}
        title={which === 'cv' ? 'CV preview' : 'Cover letter preview'}
        src={previewUrl(applicationId, which)}
        className="h-[600px] w-full rounded-md border border-slate-300 bg-white"
      />
    </section>
  )
}
```

- [ ] **Step 5: Wire it into the review page**

In `frontend/src/pages/ApplicationReview.tsx`:

Add `useNavigate` to the **existing** `react-router-dom` import rather than writing a second
import statement for the same module, and add the component import:

```tsx
import { Link, useNavigate, useParams } from 'react-router-dom'
import { DocumentPreview } from '../components/DocumentPreview'
```

Add the navigate hook beside the existing state:

```tsx
  const navigate = useNavigate()
```

Replace the approve button's handler. The existing `run(...)` helper expects a function returning `Promise<Application>` and approve no longer does, so approve gets its own handler:

```tsx
  const approve = async () => {
    if (!app) return
    setBusy(true); setError(null); setMessage(null)
    try {
      const archived = await approveApplication(app.id)
      navigate(`/archive/${archived.id}`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
```

Point the approve button at it, and say what it costs:

```tsx
          <button onClick={approve} disabled={busy}
                  className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-50">
            {busy ? 'Rendering…' : 'Approve'}
          </button>
```

Render the preview directly above the `Your cover letter` section, so you read the document before you write into it:

```tsx
      <DocumentPreview applicationId={app.id} />
```

Leave the requirement→evidence section and the letter textarea exactly as they are — the preview is an addition, not a replacement. The evidence list is still where you see which requirements nothing answers.

- [ ] **Step 6: Build**

```bash
cd frontend && npm run build
```

Expected: clean typecheck and bundle. A type error on `approveApplication`'s new return type means a caller was missed — fix the caller rather than widening the type.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat: preview the real document before approving it"
```

---

## Task 9: The archive page

The interview view. This is the last task.

**Files:**
- Create: `frontend/src/pages/ArchivePage.tsx`, `frontend/src/pages/ArchiveEntryPage.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/pages/JobList.tsx`

**Interfaces:**
- Consumes: `fetchArchive`, `fetchArchiveEntry`, `cvPdfUrl`, `letterPdfUrl` (Task 8).
- Produces: routes `/archive` and `/archive/:id`. This task ends the plan.

- [ ] **Step 1: Write the list page**

Create `frontend/src/pages/ArchivePage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchArchive } from '../api/archiveClient'
import type { ArchiveSummary } from '../archiveTypes'
import type { Page } from '../types'

export function ArchivePage() {
  const [data, setData] = useState<Page<ArchiveSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchArchive().then(setData).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
        <span className="font-medium text-slate-900">Archive</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Archive</h1>
      <p className="mt-1 text-sm text-slate-600">
        What you approved, exactly as it was — including the ad as it read that day.
      </p>

      {error && (
        <p className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>
      )}

      {data && data.content.length === 0 && (
        <p className="py-12 text-center text-sm text-slate-500">
          Nothing archived yet. Approving an application freezes it here.
        </p>
      )}

      <ul className="mt-6 divide-y divide-slate-200">
        {data?.content.map((entry) => (
          <li key={entry.id} className="py-4">
            <Link to={`/archive/${entry.id}`} className="text-base font-medium text-slate-900 hover:underline">
              {entry.jobTitle}
            </Link>
            <p className="mt-0.5 text-sm text-slate-600">
              {entry.employerName ?? 'Unknown employer'} ·{' '}
              {new Date(entry.approvedAt).toLocaleDateString()} · {entry.coveragePercent}% covered
            </p>
          </li>
        ))}
      </ul>
    </div>
  )
}
```

- [ ] **Step 2: Write the entry page**

Create `frontend/src/pages/ArchiveEntryPage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { cvPdfUrl, fetchArchiveEntry, letterPdfUrl } from '../api/archiveClient'
import type { ArchiveDetail } from '../archiveTypes'

export function ArchiveEntryPage() {
  const { id } = useParams<{ id: string }>()
  const [entry, setEntry] = useState<ArchiveDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!id) return
    fetchArchiveEntry(Number(id)).then(setEntry).catch((e: Error) => setError(e.message))
  }, [id])

  if (error) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-red-700">{error}</p>
  }
  if (!entry) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-slate-500">Loading…</p>
  }

  const copyLetter = async () => {
    await navigator.clipboard.writeText(entry.letterText ?? '')
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/archive" className="text-slate-600 hover:underline">← Archive</Link>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{entry.jobTitle}</h1>
      <p className="mt-1 text-sm text-slate-600">
        {entry.employerName ?? 'Unknown employer'} · approved{' '}
        {new Date(entry.approvedAt).toLocaleString()} · {entry.coveragePercent}% covered
      </p>

      <div className="mt-5 flex flex-wrap gap-2">
        <a href={cvPdfUrl(entry.id)} target="_blank" rel="noreferrer"
           className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700">
          Open the CV
        </a>
        <a href={letterPdfUrl(entry.id)} target="_blank" rel="noreferrer"
           className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50">
          Open the cover letter
        </a>
        <button onClick={copyLetter}
                className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50">
          {copied ? 'Copied' : 'Copy the letter text'}
        </button>
      </div>

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          The ad you answered
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          Copied when you approved. The live posting may since have been edited or removed.
        </p>
        <p className="whitespace-pre-wrap rounded-md bg-slate-50 p-4 text-sm leading-relaxed text-slate-800">
          {entry.jobDescriptionText ?? 'No description was captured.'}
        </p>
        {entry.jobApplyUrl && (
          <p className="mt-2 break-all text-xs text-slate-500">Applied via {entry.jobApplyUrl}</p>
        )}
      </section>

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          File fingerprints
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          SHA-256 of the stored files — so you can say which documents were sent, not just what
          they contained.
        </p>
        <dl className="space-y-1 font-mono text-xs text-slate-600">
          <div><dt className="inline text-slate-500">CV </dt><dd className="inline break-all">{entry.cvPdfSha256}</dd></div>
          <div><dt className="inline text-slate-500">Letter </dt><dd className="inline break-all">{entry.letterPdfSha256}</dd></div>
        </dl>
        {entry.renderedBy && (
          <p className="mt-2 text-xs text-slate-500">Rendered by {entry.renderedBy}</p>
        )}
      </section>
    </div>
  )
}
```

- [ ] **Step 3: Add the routes and the nav link**

In `frontend/src/App.tsx`, add the imports and routes:

```tsx
import { ArchivePage } from './pages/ArchivePage'
import { ArchiveEntryPage } from './pages/ArchiveEntryPage'
```

```tsx
        <Route path="/archive" element={<ArchivePage />} />
        <Route path="/archive/:id" element={<ArchiveEntryPage />} />
```

In `frontend/src/pages/JobList.tsx`, add to the nav beside the existing links:

```tsx
        <Link to="/archive" className="text-slate-600 hover:underline">Archive</Link>
```

- [ ] **Step 4: Build**

```bash
cd frontend && npm run build
```

Expected: clean typecheck and bundle.

- [ ] **Step 5: Run the whole backend suite once more and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
cd ..
git add frontend/src
git commit -m "feat: add the archive view for referring back during interviews"
```

---

## Verification against the spec's success criteria

Run through these by hand once the tasks are done. Numbers match Section 10 of the spec.

| # | Criterion | Where it is proved |
|---|---|---|
| 1 | Suite passes with no network, on a machine with no browser — browser tests skip | Task 5's `assumeTrue`; every other test mocks `PdfRenderer` |
| 2 | Preview and approve produce the same HTML | Structural: `approve` calls `cvHtml`/`letterHtml`. `ArchiveServiceTest.thePreviewAndTheApprovedDocumentComeFromTheSameHtml` |
| 3 | Approving renders two PDFs, stores both with SHA-256, sets `APPROVED` | `ArchiveServiceTest.approvingFreezesTheDocumentsAndTheAd`, `approvingHashesEachFile`, `approvingFlipsTheApplicationToApproved` |
| 4 | Text extracted from the rendered CV contains the bullets verbatim | `PdfRendererTest.theBulletsSurviveIntoTheFile` |
| 5 | å, ä, ö survive a render-then-extract round trip | `PdfRendererTest.swedishCharactersSurviveTheRoundTrip` |
| 6 | The archived ad is unchanged after an ingest rewrites the description | Copied at approval — `ArchiveServiceTest.approvingFreezesTheDocumentsAndTheAd` plus a hand check against the live database |
| 7 | The archive survives discarding and re-tailoring | `ArchiveServiceTest.theArchiveSurvivesTheApplicationBeingDeleted` |
| 8 | A second approval for the same job adds a second row | `ArchiveSchemaMigrationTest.oneJobMayBeArchivedMoreThanOnce` (no unique constraint) |
| 9 | With no browser, preview and approval return 503 and earlier slices work | `ArchiveApiTest.approvingWithNoBrowserIsFiveOhThree`; hand check with Chromium absent |
| 10 | No path reads, follows or submits `apply_url` | `grep -rn "getApplyUrl\|apply_url" backend/src/main/java/se/caiowain/jobseeker/{render,archive}` returns only the archive's own column and DTO |

**Two things to check by hand against the real database**, because no test can prove them:

- Approve a real application, then run an ingest that updates that posting, then reopen the
  archive entry and confirm the ad text has not moved.
- Open both PDFs and read them. The whole slice exists so that what you see is what is sent;
  the only way to know it worked is to look.
