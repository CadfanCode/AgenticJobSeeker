# CV Profile Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upload a CV PDF, extract it into a structured profile with an LLM, verify the extraction against the source text, and review and correct it in the dashboard.

**Architecture:** A synchronous pipeline of five isolated units — `CvUploadService` (store + hash), `PdfTextExtractor` (PDFBox, behind an interface), `CvProfileExtractor` (the only LLM caller), `ExtractionValidator` (pure functions, no I/O), `CvProfileService` (persistence and status). The validator asserts every extracted employer, title and date literally occurs in the source text and flags what doesn't, so an invented employer surfaces in the review UI instead of being silently trusted.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-model-anthropic`, over `anthropic-java-core:2.52.0`), Apache PDFBox 3.0.8, PostgreSQL, Flyway, WireMock, Testcontainers, React 19 + Vite 8 + Tailwind 4.

**Spec:** `docs/superpowers/specs/2026-08-27-cv-profile-ingestion-design.md`

## Global Constraints

Everything from Slice 1 still applies. Repeated here because they are easy to get wrong:

- **Spring Boot version is exactly `4.1.1`.** Never write `4.1.1.RELEASE` — that string appears in Initializr metadata but resolves to nothing in Maven Central.
- **Jackson 3, not Jackson 2.** Import `tools.jackson.databind.*`, never `com.fasterxml.jackson.databind.*`. `JsonNode.asText()` is `asString()`.
- **Boot 4 test annotations moved.** `@AutoConfigureMockMvc` is `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- **Maven is not installed.** Build with `./mvnw` from `backend/`, after `export JAVA_HOME=/usr/lib/jvm/default`.
- **PostgreSQL runs on port 5433**, not 5432 (5432 is taken by an unrelated project).
- **No test may call a live LLM or any network host.** `ChatClient` is stubbed in every test.
- **Spring AI 2.0.1 facts, verified against the jars — do not guess these:**
  - Config prefixes are `spring.ai.anthropic` (connection) and `spring.ai.anthropic.chat` (chat).
  - `ChatClient.Builder` **is** injectable even with no API key configured — the autoconfiguration creates `ChatModel`, `AnthropicChatModel` and `ChatClient.Builder` beans regardless. Availability must therefore be decided by reading the configured key, **not** by checking for a bean.
  - `ChatClientRequestSpec.options(B)` takes a **`ChatOptions.Builder`**, not a built `ChatOptions`. There is exactly one overload.
  - Structured output: `.call().entity(SomeRecord.class)`.
  - `com.anthropic.models.messages.Model.CLAUDE_OPUS_5` is a constant.
  - `OutputConfig.Effort` values are `LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`.
  - Adaptive thinking: `ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build())`.
- **Model is `claude-opus-5`.** Do not substitute a cheaper model.
- **Dates are stored as `VARCHAR(32)` exactly as written on the CV.** Never parse them into `DATE`.
- **TDD:** failing test, watch it fail, minimal implementation, watch it pass, commit.
- Every task ends with a commit.

---

## File Structure

**Backend** (`backend/src/main/java/se/caiowain/jobseeker/`):

| Path | Responsibility |
|---|---|
| `profile/domain/CvDocument.java` | Uploaded PDF bytes, hash, extracted text |
| `profile/domain/CvProfile.java` | Profile root: identity, summary, status |
| `profile/domain/CvExperience.java` | One role, with verification flag |
| `profile/domain/CvExperienceBullet.java` | One bullet of one role |
| `profile/domain/CvEducation.java` | One education entry |
| `profile/domain/CvSkill.java` | One skill |
| `profile/domain/ProfileStatus.java` | Enum: NEEDS_REVIEW, READY, EXTRACTION_FAILED |
| `profile/repo/*Repository.java` | Spring Data repositories |
| `profile/extract/PdfTextExtractor.java` | Interface: bytes → text |
| `profile/extract/PdfBoxTextExtractor.java` | PDFBox implementation |
| `profile/extract/ExtractedProfile.java` | LLM output shape (records) |
| `profile/extract/ExtractionValidator.java` | Pure hallucination guard |
| `profile/extract/CvProfileExtractor.java` | The only LLM caller |
| `profile/extract/ExtractionUnavailableException.java` | No API key configured |
| `profile/CvUploadService.java` | Accept, validate, hash, persist document |
| `profile/CvProfileService.java` | Persist profile, transitions, corrections |
| `profile/api/ProfileController.java` | `/api/profile/**` |
| `profile/api/dto/*.java` | Request/response DTOs |

**Frontend** (`frontend/src/`): `profileTypes.ts`, `api/profileClient.ts`, `pages/ProfilePage.tsx`, `components/ProfileUpload.tsx`, `components/ProfileReview.tsx`.

---

## Task 1: Dependencies, configuration and the V5 schema

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V5__cv_profile.sql`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/CvSchemaMigrationTest.java`

**Interfaces:**
- Consumes: the Slice 1 `AbstractIntegrationTest`
- Produces: tables `cv_document`, `cv_profile`, `cv_experience`, `cv_experience_bullet`, `cv_education`, `cv_skill`

- [ ] **Step 1: Add the dependencies**

In `backend/pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
    <version>2.0.1</version>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.8</version>
</dependency>
```

- [ ] **Step 2: Add configuration**

Append to `backend/src/main/resources/application.yml`, as a top-level sibling of `spring:`
and `jobseeker:`:

```yaml
spring.ai.anthropic:
  api-key: ${ANTHROPIC_API_KEY:}
  chat.options:
    model: claude-opus-5
    max-tokens: 16000
```

and under the existing `jobseeker:` block, as a sibling of `ingest:`:

```yaml
  profile:
    max-upload-bytes: 10485760
    extraction-effort: HIGH
```

Also raise the multipart limits under the existing `spring:` block:

```yaml
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 12MB
```

- [ ] **Step 3: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/profile/CvSchemaMigrationTest.java`:

```java
package se.caiowain.jobseeker.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CvSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAllProfileTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "cv_document", "cv_profile", "cv_experience",
                "cv_experience_bullet", "cv_education", "cv_skill");
    }

    @Test
    void documentHashIsUnique() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'cv_document' and indexdef like '%UNIQUE%sha256%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void datesAreStoredAsTextNotDate() {
        // CVs write fuzzy dates ("2019", "Mar 2020-present"). Parsing them would
        // invent precision the source does not have and break the substring guard.
        String type = jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = 'cv_experience' and column_name = 'start_date'
                """, String.class);
        assertThat(type).isEqualTo("character varying");
    }

    @Test
    void applicationStartsWithoutAnApiKey() {
        // Success criterion 6: Spring AI autoconfiguration must not block startup.
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }
}
```

- [ ] **Step 4: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvSchemaMigrationTest
```

Expected: FAIL — the tables do not exist.

- [ ] **Step 5: Write the migration**

`backend/src/main/resources/db/migration/V5__cv_profile.sql`:

```sql
CREATE TABLE cv_document (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    filename       VARCHAR(512) NOT NULL,
    content_type   VARCHAR(128) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    content        BYTEA        NOT NULL,
    extracted_text TEXT,
    uploaded_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE cv_profile (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cv_document_id  BIGINT       NOT NULL REFERENCES cv_document (id) ON DELETE CASCADE,
    full_name       VARCHAR(256),
    headline        VARCHAR(512),
    email           VARCHAR(256),
    phone           VARCHAR(64),
    location        VARCHAR(256),
    summary         TEXT,
    language        VARCHAR(8),
    status          VARCHAR(32)  NOT NULL,
    model_used      VARCHAR(64),
    extracted_at    TIMESTAMPTZ,
    reviewed_at     TIMESTAMPTZ
);

CREATE TABLE cv_experience (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cv_profile_id      BIGINT       NOT NULL REFERENCES cv_profile (id) ON DELETE CASCADE,
    employer           VARCHAR(512),
    title              VARCHAR(512),
    start_date         VARCHAR(32),
    end_date           VARCHAR(32),
    is_current         BOOLEAN      NOT NULL DEFAULT FALSE,
    location           VARCHAR(256),
    ordinal            INTEGER      NOT NULL DEFAULT 0,
    verified           BOOLEAN      NOT NULL DEFAULT TRUE,
    verification_notes TEXT
);

CREATE TABLE cv_experience_bullet (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cv_experience_id BIGINT  NOT NULL REFERENCES cv_experience (id) ON DELETE CASCADE,
    text             TEXT    NOT NULL,
    ordinal          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE cv_education (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cv_profile_id      BIGINT       NOT NULL REFERENCES cv_profile (id) ON DELETE CASCADE,
    institution        VARCHAR(512),
    degree             VARCHAR(512),
    field_of_study     VARCHAR(512),
    start_date         VARCHAR(32),
    end_date           VARCHAR(32),
    ordinal            INTEGER      NOT NULL DEFAULT 0,
    verified           BOOLEAN      NOT NULL DEFAULT TRUE,
    verification_notes TEXT
);

CREATE TABLE cv_skill (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cv_profile_id BIGINT       NOT NULL REFERENCES cv_profile (id) ON DELETE CASCADE,
    name          VARCHAR(256) NOT NULL,
    category      VARCHAR(128),
    ordinal       INTEGER      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_cv_document_sha256      ON cv_document (sha256);
CREATE        INDEX ix_cv_profile_document     ON cv_profile (cv_document_id);
CREATE        INDEX ix_cv_experience_profile   ON cv_experience (cv_profile_id);
CREATE        INDEX ix_cv_bullet_experience    ON cv_experience_bullet (cv_experience_id);
CREATE        INDEX ix_cv_education_profile    ON cv_education (cv_profile_id);
CREATE        INDEX ix_cv_skill_profile        ON cv_skill (cv_profile_id);
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvSchemaMigrationTest
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/main/resources backend/src/test
git commit -m "feat: add Spring AI and PDFBox deps with V5 CV profile schema"
```

---

## Task 2: Profile domain entities and repositories

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/domain/{ProfileStatus,CvDocument,CvProfile,CvExperience,CvExperienceBullet,CvEducation,CvSkill}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/repo/{CvDocumentRepository,CvProfileRepository}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/CvProfilePersistenceTest.java`

**Interfaces:**
- Consumes: the V5 schema from Task 1
- Produces:
  - `ProfileStatus` enum: `NEEDS_REVIEW`, `READY`, `EXTRACTION_FAILED`
  - `CvDocumentRepository.findBySha256(String) : Optional<CvDocument>`
  - `CvProfileRepository.findFirstByOrderByIdDesc() : Optional<CvProfile>`
  - `CvProfile.getExperiences() : List<CvExperience>` (cascade-all, orphan removal)
  - `CvExperience.getBullets() : List<CvExperienceBullet>` (cascade-all, orphan removal)

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/profile/CvProfilePersistenceTest.java`:

```java
package se.caiowain.jobseeker.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CvProfilePersistenceTest extends AbstractIntegrationTest {

    @Autowired CvDocumentRepository documents;
    @Autowired CvProfileRepository profiles;

    private CvDocument document(String sha) {
        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1234L);
        doc.setSha256(sha);
        doc.setContent(new byte[]{1, 2, 3});
        doc.setExtractedText("Acme AB Senior Engineer 2019");
        doc.setUploadedAt(Instant.now());
        return documents.save(doc);
    }

    @Test
    void persistsProfileWithExperienceBulletsEducationAndSkills() {
        CvProfile profile = new CvProfile();
        profile.setCvDocument(document("sha-a"));
        profile.setFullName("Cai");
        profile.setLanguage("en");
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profile.setExtractedAt(Instant.now());

        CvExperience exp = new CvExperience();
        exp.setEmployer("Acme AB");
        exp.setTitle("Senior Engineer");
        exp.setStartDate("2019");
        exp.setEndDate("present");
        exp.setCurrent(true);
        exp.setOrdinal(0);
        exp.setVerified(true);
        profile.addExperience(exp);

        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built the ingestion pipeline");
        bullet.setOrdinal(0);
        exp.addBullet(bullet);

        CvEducation edu = new CvEducation();
        edu.setInstitution("KTH");
        edu.setDegree("MSc");
        edu.setOrdinal(0);
        edu.setVerified(true);
        profile.addEducation(edu);

        CvSkill skill = new CvSkill();
        skill.setName("Java");
        skill.setOrdinal(0);
        profile.addSkill(skill);

        profiles.save(profile);

        CvProfile loaded = profiles.findFirstByOrderByIdDesc().orElseThrow();
        assertThat(loaded.getFullName()).isEqualTo("Cai");
        assertThat(loaded.getExperiences()).hasSize(1);
        assertThat(loaded.getExperiences().getFirst().getBullets()).hasSize(1);
        assertThat(loaded.getEducation()).hasSize(1);
        assertThat(loaded.getSkills()).hasSize(1);
    }

    @Test
    void documentLookupBySha256Works() {
        document("sha-b");
        assertThat(documents.findBySha256("sha-b")).isPresent();
        assertThat(documents.findBySha256("missing")).isEmpty();
    }

    @Test
    void newestProfileWins() {
        CvProfile first = new CvProfile();
        first.setCvDocument(document("sha-c"));
        first.setFullName("Older");
        first.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(first);

        CvProfile second = new CvProfile();
        second.setCvDocument(document("sha-d"));
        second.setFullName("Newer");
        second.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(second);

        assertThat(profiles.findFirstByOrderByIdDesc().orElseThrow().getFullName())
                .isEqualTo("Newer");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvProfilePersistenceTest
```

Expected: compilation failure — the domain classes do not exist.

- [ ] **Step 3: Write `ProfileStatus` and `CvDocument`**

```java
// profile/domain/ProfileStatus.java
package se.caiowain.jobseeker.profile.domain;

public enum ProfileStatus { NEEDS_REVIEW, READY, EXTRACTION_FAILED }
```

```java
// profile/domain/CvDocument.java
package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cv_document")
public class CvDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, unique = true, length = 64)
    private String sha256;

    @Lob
    @Column(nullable = false)
    private byte[] content;

    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
```

- [ ] **Step 4: Write `CvProfile`**

```java
package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cv_profile")
public class CvProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cv_document_id", nullable = false)
    private CvDocument cvDocument;

    @Column(name = "full_name", length = 256) private String fullName;
    @Column(length = 512) private String headline;
    @Column(length = 256) private String email;
    @Column(length = 64) private String phone;
    @Column(length = 256) private String location;
    @Column(columnDefinition = "text") private String summary;
    @Column(length = 8) private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProfileStatus status = ProfileStatus.NEEDS_REVIEW;

    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "extracted_at") private Instant extractedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;

    @OneToMany(mappedBy = "cvProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvExperience> experiences = new ArrayList<>();

    @OneToMany(mappedBy = "cvProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvEducation> education = new ArrayList<>();

    @OneToMany(mappedBy = "cvProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvSkill> skills = new ArrayList<>();

    public void addExperience(CvExperience e) { e.setCvProfile(this); experiences.add(e); }
    public void addEducation(CvEducation e) { e.setCvProfile(this); education.add(e); }
    public void addSkill(CvSkill s) { s.setCvProfile(this); skills.add(s); }

    public Long getId() { return id; }
    public CvDocument getCvDocument() { return cvDocument; }
    public void setCvDocument(CvDocument cvDocument) { this.cvDocument = cvDocument; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public ProfileStatus getStatus() { return status; }
    public void setStatus(ProfileStatus status) { this.status = status; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public Instant getExtractedAt() { return extractedAt; }
    public void setExtractedAt(Instant extractedAt) { this.extractedAt = extractedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public List<CvExperience> getExperiences() { return experiences; }
    public List<CvEducation> getEducation() { return education; }
    public List<CvSkill> getSkills() { return skills; }
}
```

- [ ] **Step 5: Write `CvExperience`, `CvExperienceBullet`, `CvEducation`, `CvSkill`**

```java
// profile/domain/CvExperience.java
package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cv_experience")
public class CvExperience {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(length = 512) private String employer;
    @Column(length = 512) private String title;
    /** Stored exactly as written on the CV. Never parsed into a date. */
    @Column(name = "start_date", length = 32) private String startDate;
    @Column(name = "end_date", length = 32) private String endDate;
    @Column(name = "is_current", nullable = false) private boolean current;
    @Column(length = 256) private String location;
    @Column(nullable = false) private int ordinal;
    @Column(nullable = false) private boolean verified = true;
    @Column(name = "verification_notes", columnDefinition = "text") private String verificationNotes;

    @OneToMany(mappedBy = "cvExperience", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvExperienceBullet> bullets = new ArrayList<>();

    public void addBullet(CvExperienceBullet b) { b.setCvExperience(this); bullets.add(b); }

    public Long getId() { return id; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getEmployer() { return employer; }
    public void setEmployer(String employer) { this.employer = employer; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String v) { this.verificationNotes = v; }
    public List<CvExperienceBullet> getBullets() { return bullets; }
}
```

```java
// profile/domain/CvExperienceBullet.java
package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_experience_bullet")
public class CvExperienceBullet {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_experience_id", nullable = false)
    private CvExperience cvExperience;

    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public CvExperience getCvExperience() { return cvExperience; }
    public void setCvExperience(CvExperience cvExperience) { this.cvExperience = cvExperience; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
```

```java
// profile/domain/CvEducation.java
package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_education")
public class CvEducation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(length = 512) private String institution;
    @Column(length = 512) private String degree;
    @Column(name = "field_of_study", length = 512) private String fieldOfStudy;
    @Column(name = "start_date", length = 32) private String startDate;
    @Column(name = "end_date", length = 32) private String endDate;
    @Column(nullable = false) private int ordinal;
    @Column(nullable = false) private boolean verified = true;
    @Column(name = "verification_notes", columnDefinition = "text") private String verificationNotes;

    public Long getId() { return id; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }
    public String getFieldOfStudy() { return fieldOfStudy; }
    public void setFieldOfStudy(String fieldOfStudy) { this.fieldOfStudy = fieldOfStudy; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String v) { this.verificationNotes = v; }
}
```

```java
// profile/domain/CvSkill.java
package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_skill")
public class CvSkill {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(nullable = false, length = 256) private String name;
    @Column(length = 128) private String category;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
```

- [ ] **Step 6: Write the repositories**

```java
// profile/repo/CvDocumentRepository.java
package se.caiowain.jobseeker.profile.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.profile.domain.CvDocument;

import java.util.Optional;

public interface CvDocumentRepository extends JpaRepository<CvDocument, Long> {
    Optional<CvDocument> findBySha256(String sha256);
}
```

```java
// profile/repo/CvProfileRepository.java
package se.caiowain.jobseeker.profile.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.util.Optional;

public interface CvProfileRepository extends JpaRepository<CvProfile, Long> {
    /** The newest profile is the current one. */
    Optional<CvProfile> findFirstByOrderByIdDesc();
    Optional<CvProfile> findFirstByCvDocumentIdOrderByIdDesc(Long cvDocumentId);
}
```

- [ ] **Step 7: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvProfilePersistenceTest
```

Expected: PASS, 3 tests. `ddl-auto: validate` also proves the entities match the V5 migration.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat: add CV profile domain entities and repositories"
```

---

## Task 3: PDF text extraction

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/extract/{PdfTextExtractor,PdfBoxTextExtractor,PdfTextExtractionException}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/extract/PdfBoxTextExtractorTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `PdfTextExtractor.extract(byte[]) : String`, throwing `PdfTextExtractionException` when the PDF yields no usable text

- [ ] **Step 1: Write the failing test**

The fixture PDF is generated in-test with PDFBox, so the suite is deterministic and no real CV
enters the repository.

`backend/src/test/java/se/caiowain/jobseeker/profile/extract/PdfBoxTextExtractorTest.java`:

```java
package se.caiowain.jobseeker.profile.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBoxTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfBoxTextExtractor();

    /** Builds a small PDF in memory so the test needs no committed binary. */
    static byte[] pdfWithLines(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(16f);
                cs.newLineAtOffset(50, 750);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] emptyPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsTextFromAPdf() throws Exception {
        byte[] pdf = pdfWithLines(List.of("Cai Wain", "Senior Engineer at Acme AB", "2019 - present"));

        String text = extractor.extract(pdf);

        assertThat(text).contains("Cai Wain");
        assertThat(text).contains("Senior Engineer at Acme AB");
        assertThat(text).contains("2019 - present");
    }

    @Test
    void preservesLineStructure() throws Exception {
        String text = extractor.extract(pdfWithLines(List.of("Line one", "Line two")));
        assertThat(text.lines().toList()).contains("Line one", "Line two");
    }

    @Test
    void rejectsAPdfWithNoTextLayer() throws Exception {
        assertThatThrownBy(() -> extractor.extract(emptyPdf()))
                .isInstanceOf(PdfTextExtractionException.class)
                .hasMessageContaining("no extractable text");
    }

    @Test
    void rejectsBytesThatAreNotAPdf() {
        assertThatThrownBy(() -> extractor.extract("this is not a pdf".getBytes()))
                .isInstanceOf(PdfTextExtractionException.class);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=PdfBoxTextExtractorTest
```

Expected: compilation failure — the extractor types do not exist.

- [ ] **Step 3: Write the interface and exception**

```java
// profile/extract/PdfTextExtractor.java
package se.caiowain.jobseeker.profile.extract;

/**
 * Turns PDF bytes into plain text.
 *
 * <p>An interface rather than a class so Slice 2b can accept other input formats, and so
 * tests can substitute a deterministic stand-in without constructing PDFs.
 */
public interface PdfTextExtractor {
    String extract(byte[] pdfBytes);
}
```

```java
// profile/extract/PdfTextExtractionException.java
package se.caiowain.jobseeker.profile.extract;

public class PdfTextExtractionException extends RuntimeException {
    public PdfTextExtractionException(String message) { super(message); }
    public PdfTextExtractionException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: Write the PDFBox implementation**

```java
package se.caiowain.jobseeker.profile.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxTextExtractor implements PdfTextExtractor {

    /** Below this, the document is almost certainly a scan with no text layer. */
    private static final int MINIMUM_USEFUL_CHARACTERS = 20;

    @Override
    public String extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfTextExtractionException("Uploaded file is empty");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            String normalised = text == null ? "" : text.strip();

            if (normalised.length() < MINIMUM_USEFUL_CHARACTERS) {
                throw new PdfTextExtractionException(
                        "The PDF contains no extractable text — is it a scanned image?");
            }
            return normalised;
        } catch (PdfTextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfTextExtractionException("Could not read the PDF: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=PdfBoxTextExtractorTest
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: add PDFBox text extraction with scanned-PDF detection"
```

---

## Task 4: The extraction result shape and the hallucination guard

The highest-value tests in the slice. `ExtractionValidator` is pure — no Spring, no I/O.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/extract/ExtractedProfile.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/extract/ExtractionValidator.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/extract/ExtractionValidatorTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `ExtractedProfile` record with nested `ExtractedExperience`, `ExtractedEducation`, `ExtractedSkill` records
  - `ExtractionValidator.verify(ExtractedProfile, String sourceText) : VerificationReport`
  - `VerificationReport.notesForExperience(int index) : Optional<String>` and `notesForEducation(int index) : Optional<String>` — empty means verified

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/profile/extract/ExtractionValidatorTest.java`:

```java
package se.caiowain.jobseeker.profile.extract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    private static final String SOURCE = """
            Cai Wain
            Senior Engineer, Acme AB
            2019 - present
            Built the ingestion pipeline that reduced
            duplicate postings by 40%
            KTH Royal Institute of Technology, MSc Computer Science, 2014 - 2019
            """;

    private ExtractedProfile profileWith(List<ExtractedProfile.ExtractedExperience> experiences,
                                         List<ExtractedProfile.ExtractedEducation> education) {
        return new ExtractedProfile("Cai Wain", "Senior Engineer", null, null, null,
                null, "en", experiences, education, List.of());
    }

    @Test
    void verifiesAnExperienceWhoseFieldsAllAppearInTheSource() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2019", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void flagsAnInventedEmployer() {
        // The failure mode that matters: a plausible employer that is not in the CV.
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Globex Corporation", "Senior Engineer", "2019", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isPresent();
        assertThat(report.notesForExperience(0).orElseThrow()).contains("employer");
        assertThat(report.notesForExperience(0).orElseThrow()).contains("Globex Corporation");
    }

    @Test
    void flagsAShiftedDate() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2017", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0).orElseThrow()).contains("startDate");
    }

    @Test
    void doesNotFlagReflowedBulletText() {
        // Extraction legitimately joins wrapped lines; bullets are reviewed by eye, not asserted.
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2019", "present", true, null,
                        List.of("Built the ingestion pipeline that reduced duplicate postings by 40%"))),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void matchingIsCaseAndWhitespaceInsensitive() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "acme   ab", "SENIOR ENGINEER", "2019", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void verifiesAndFlagsEducationTheSameWay() {
        var report = validator.verify(profileWith(List.of(), List.of(
                new ExtractedProfile.ExtractedEducation("KTH Royal Institute of Technology",
                        "MSc", "Computer Science", "2014", "2019"),
                new ExtractedProfile.ExtractedEducation("Hogwarts",
                        "MSc", "Wizardry", "2014", "2019"))), SOURCE);

        assertThat(report.notesForEducation(0)).isEmpty();
        assertThat(report.notesForEducation(1).orElseThrow()).contains("institution");
    }

    @Test
    void nullAndBlankFieldsAreNotFlagged() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", null, null, "", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void reportsSeveralOffendingFieldsInOneNote() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Globex", "Chief Wizard", "1999", "present", true, null, List.of())),
                List.of()), SOURCE);

        String note = report.notesForExperience(0).orElseThrow();
        assertThat(note).contains("employer").contains("title").contains("startDate");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=ExtractionValidatorTest
```

Expected: compilation failure — the types do not exist.

- [ ] **Step 3: Write `ExtractedProfile`**

```java
package se.caiowain.jobseeker.profile.extract;

import java.util.List;

/**
 * The shape the LLM is asked to produce. Kept separate from the JPA entities so the
 * model's output can be validated before anything is persisted.
 */
public record ExtractedProfile(
        String fullName,
        String headline,
        String email,
        String phone,
        String location,
        String summary,
        String language,
        List<ExtractedExperience> experiences,
        List<ExtractedEducation> education,
        List<ExtractedSkill> skills
) {

    public record ExtractedExperience(
            String employer,
            String title,
            String startDate,
            String endDate,
            boolean current,
            String location,
            List<String> bullets
    ) {
    }

    public record ExtractedEducation(
            String institution,
            String degree,
            String fieldOfStudy,
            String startDate,
            String endDate
    ) {
    }

    public record ExtractedSkill(String name, String category) {
    }
}
```

- [ ] **Step 4: Write `ExtractionValidator`**

```java
package se.caiowain.jobseeker.profile.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hallucination guard.
 *
 * <p>The failure mode worth engineering against is not a mangled layout — it is the model
 * inventing an employer or shifting a date into something plausible, which is exactly the
 * error a person skims past when reviewing their own CV. Every extracted employer, title
 * and date literal must occur in the source text; anything that does not is flagged.
 *
 * <p>The check is deliberately one-directional. It cannot prove the extraction is complete,
 * only that what it produced is present in the source. Detecting omissions is what human
 * review is for.
 *
 * <p>Bullet text is not checked: extraction legitimately reflows and merges wrapped lines,
 * so a substring assertion would produce constant false positives.
 */
public class ExtractionValidator {

    public VerificationReport verify(ExtractedProfile profile, String sourceText) {
        String haystack = fold(sourceText);

        Map<Integer, String> experienceNotes = new LinkedHashMap<>();
        List<ExtractedProfile.ExtractedExperience> experiences =
                profile.experiences() == null ? List.of() : profile.experiences();
        for (int i = 0; i < experiences.size(); i++) {
            var experience = experiences.get(i);
            List<String> offenders = new ArrayList<>();
            checkField("employer", experience.employer(), haystack, offenders);
            checkField("title", experience.title(), haystack, offenders);
            checkField("startDate", experience.startDate(), haystack, offenders);
            checkField("endDate", experience.endDate(), haystack, offenders);
            if (!offenders.isEmpty()) {
                experienceNotes.put(i, note(offenders));
            }
        }

        Map<Integer, String> educationNotes = new LinkedHashMap<>();
        List<ExtractedProfile.ExtractedEducation> education =
                profile.education() == null ? List.of() : profile.education();
        for (int i = 0; i < education.size(); i++) {
            var entry = education.get(i);
            List<String> offenders = new ArrayList<>();
            checkField("institution", entry.institution(), haystack, offenders);
            checkField("degree", entry.degree(), haystack, offenders);
            checkField("fieldOfStudy", entry.fieldOfStudy(), haystack, offenders);
            checkField("startDate", entry.startDate(), haystack, offenders);
            checkField("endDate", entry.endDate(), haystack, offenders);
            if (!offenders.isEmpty()) {
                educationNotes.put(i, note(offenders));
            }
        }

        return new VerificationReport(experienceNotes, educationNotes);
    }

    private void checkField(String fieldName, String value, String haystack, List<String> offenders) {
        if (value == null || value.isBlank()) {
            return;
        }
        String needle = fold(value);
        if (needle.isEmpty()) {
            return;
        }
        if (!haystack.contains(needle)) {
            offenders.add(fieldName + " \"" + value.strip() + "\"");
        }
    }

    private String note(List<String> offenders) {
        return "Not found in the uploaded CV: " + String.join(", ", offenders);
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

    /** Empty note means verified. */
    public record VerificationReport(Map<Integer, String> experienceNotes,
                                     Map<Integer, String> educationNotes) {

        public Optional<String> notesForExperience(int index) {
            return Optional.ofNullable(experienceNotes.get(index));
        }

        public Optional<String> notesForEducation(int index) {
            return Optional.ofNullable(educationNotes.get(index));
        }
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=ExtractionValidatorTest
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: add extraction result shape and hallucination guard"
```

---

## Task 5: The LLM extractor

The only component that calls a model. Every test stubs `ChatClient`; none reaches the network.

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/extract/{CvProfileExtractor,ExtractionUnavailableException}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/extract/ValidatorConfig.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/extract/CvProfileExtractorTest.java`

**Interfaces:**
- Consumes: `ExtractedProfile` (Task 4)
- Produces:
  - `CvProfileExtractor.isAvailable() : boolean`
  - `CvProfileExtractor.extract(String sourceText) : ExtractedProfile` — throws `ExtractionUnavailableException` when no API key is configured
  - `CvProfileExtractor.modelName() : String`
  - `ExtractionValidator` exposed as a Spring bean by `ValidatorConfig`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/profile/extract/CvProfileExtractorTest.java`:

```java
package se.caiowain.jobseeker.profile.extract;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CvProfileExtractorTest {

    private static final ExtractedProfile CANNED = new ExtractedProfile(
            "Cai Wain", "Senior Engineer", null, null, null, null, "en",
            List.of(new ExtractedProfile.ExtractedExperience(
                    "Acme AB", "Senior Engineer", "2019", "present", true, null,
                    List.of("Built the ingestion pipeline"))),
            List.of(), List.of());

    /** Builds a ChatClient.Builder whose whole fluent chain is mocked. */
    private ChatClient.Builder stubbedBuilder(ExtractedProfile result) {
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(response.entity(ExtractedProfile.class)).thenReturn(result);

        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any())).thenReturn(request);
        when(request.call()).thenReturn(response);

        ChatClient client = mock(ChatClient.class);
        when(client.prompt()).thenReturn(request);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        return builder;
    }

    @Test
    void reportsUnavailableWhenNoApiKeyIsConfigured() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "", "claude-opus-5", "HIGH");

        assertThat(extractor.isAvailable()).isFalse();
        assertThatThrownBy(() -> extractor.extract("some cv text"))
                .isInstanceOf(ExtractionUnavailableException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void isAvailableWhenAKeyIsConfigured() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "sk-ant-test", "claude-opus-5", "HIGH");
        assertThat(extractor.isAvailable()).isTrue();
    }

    @Test
    void returnsTheStructuredProfileFromTheModel() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "sk-ant-test", "claude-opus-5", "HIGH");

        ExtractedProfile result = extractor.extract("Cai Wain\nSenior Engineer, Acme AB\n2019 - present");

        assertThat(result.fullName()).isEqualTo("Cai Wain");
        assertThat(result.experiences()).hasSize(1);
        assertThat(result.experiences().getFirst().employer()).isEqualTo("Acme AB");
    }

    @Test
    void passesTheSourceTextToTheModel() {
        ChatClient.Builder builder = stubbedBuilder(CANNED);
        var extractor = new CvProfileExtractor(builder, "sk-ant-test", "claude-opus-5", "HIGH");

        extractor.extract("MARKER-TEXT-12345");

        ChatClient.ChatClientRequestSpec request = builder.build().prompt();
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(request, atLeastOnce()).user(userMessage.capture());
        assertThat(userMessage.getAllValues()).anyMatch(v -> v.contains("MARKER-TEXT-12345"));
    }

    @Test
    void reportsTheConfiguredModelName() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "sk-ant-test", "claude-opus-5", "HIGH");
        assertThat(extractor.modelName()).isEqualTo("claude-opus-5");
    }

    @Test
    void wrapsModelFailuresInAnExtractionException() {
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(response.entity(ExtractedProfile.class)).thenThrow(new RuntimeException("upstream 529"));

        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any())).thenReturn(request);
        when(request.call()).thenReturn(response);

        ChatClient client = mock(ChatClient.class);
        when(client.prompt()).thenReturn(request);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);

        var extractor = new CvProfileExtractor(builder, "sk-ant-test", "claude-opus-5", "HIGH");

        assertThatThrownBy(() -> extractor.extract("text"))
                .isInstanceOf(CvProfileExtractor.ExtractionFailedException.class)
                .hasMessageContaining("upstream 529");
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvProfileExtractorTest
```

Expected: compilation failure — `CvProfileExtractor` does not exist.

- [ ] **Step 3: Write `ExtractionUnavailableException`**

```java
package se.caiowain.jobseeker.profile.extract;

/** No model credential is configured, so extraction cannot run. Maps to HTTP 503. */
public class ExtractionUnavailableException extends RuntimeException {
    public ExtractionUnavailableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write `CvProfileExtractor`**

```java
package se.caiowain.jobseeker.profile.extract;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only component in the application that calls a language model.
 *
 * <p>Availability is decided by reading the configured API key, not by checking for a bean:
 * Spring AI's autoconfiguration creates {@code ChatModel} and {@code ChatClient.Builder}
 * beans even when no key is set, so bean presence proves nothing.
 */
@Component
public class CvProfileExtractor {

    private static final String SYSTEM_PROMPT = """
            You extract structured data from a CV. You are a careful transcriber, not a writer.

            Rules:
            - Copy employer names, job titles, institutions and dates EXACTLY as they appear.
              Never normalise, expand, translate or correct them.
            - Write dates exactly as the CV writes them: "2019", "Mar 2020", "present".
              Do not convert to a standard format and do not infer missing parts.
            - Bullet points may be reflowed to repair line wrapping, but never reworded,
              summarised or embellished.
            - If a field is not present in the CV, return null. Never guess and never invent
              an employer, title, qualification or date that is not written in the source.
            - Set language to the ISO 639-1 code of the language the CV is written in.
            - Order experiences and education most recent first.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final String apiKey;
    private final String model;
    private final String effort;

    public CvProfileExtractor(ChatClient.Builder chatClientBuilder,
                              @Value("${spring.ai.anthropic.api-key:}") String apiKey,
                              @Value("${spring.ai.anthropic.chat.options.model:claude-opus-5}") String model,
                              @Value("${jobseeker.profile.extraction-effort:HIGH}") String effort) {
        this.chatClientBuilder = chatClientBuilder;
        this.apiKey = apiKey;
        this.model = model;
        this.effort = effort;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelName() {
        return model;
    }

    public ExtractedProfile extract(String sourceText) {
        if (!isAvailable()) {
            throw new ExtractionUnavailableException(
                    "CV extraction needs a model credential. Set ANTHROPIC_API_KEY and restart.");
        }
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user("Extract this CV:\n\n" + sourceText)
                    .options(AnthropicChatOptions.builder()
                            .thinking(ThinkingConfigParam.ofAdaptive(
                                    ThinkingConfigAdaptive.builder().build()))
                            .effort(OutputConfig.Effort.of(effort)))
                    .call()
                    .entity(ExtractedProfile.class);
        } catch (Exception e) {
            throw new ExtractionFailedException("Extraction failed: " + e.getMessage(), e);
        }
    }

    /** The model was reachable but the call did not produce a profile. Recoverable via re-extract. */
    public static class ExtractionFailedException extends RuntimeException {
        public ExtractionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

> If `OutputConfig.Effort.of(String)` does not exist, the enum constants are `LOW`, `MEDIUM`,
> `HIGH`, `XHIGH`, `MAX` — use `OutputConfig.Effort.valueOf(effort)` or the matching constant
> field. Let the compiler decide; do not guess a third form.

- [ ] **Step 5: Expose `ExtractionValidator` as a bean**

```java
// profile/extract/ValidatorConfig.java
package se.caiowain.jobseeker.profile.extract;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidatorConfig {

    /** Kept free of Spring annotations so its tests stay pure. */
    @Bean
    public ExtractionValidator extractionValidator() {
        return new ExtractionValidator();
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvProfileExtractorTest
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: add LLM CV extractor with availability guard"
```

---

## Task 6: Upload and profile services

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/{CvUploadService,CvProfileService,UploadRejectedException}.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/CvProfileServiceTest.java`

**Interfaces:**
- Consumes: repositories (Task 2), `PdfTextExtractor` (Task 3), `CvProfileExtractor` + `ExtractionValidator` (Tasks 4-5)
- Produces:
  - `CvUploadService.store(String filename, String contentType, byte[] content) : CvDocument` — throws `UploadRejectedException`
  - `CvUploadService.sha256(byte[]) : String`
  - `CvProfileService.ingest(CvDocument) : CvProfile` — extract, validate, persist
  - `CvProfileService.current() : Optional<CvProfile>`
  - `CvProfileService.reextract() : CvProfile`
  - `CvProfileService.approve() : CvProfile`
  - `CvProfileService.applyCorrections(CvProfile edited) : CvProfile`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/profile/CvProfileServiceTest.java`:

```java
package se.caiowain.jobseeker.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.extract.CvProfileExtractor;
import se.caiowain.jobseeker.profile.extract.ExtractedProfile;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class CvProfileServiceTest extends AbstractIntegrationTest {

    @Autowired CvUploadService uploads;
    @Autowired CvProfileService profileService;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProfileRepository profiles;

    /** The only LLM caller is replaced; no test reaches the network. */
    @MockitoBean CvProfileExtractor extractor;

    private static final String SOURCE = """
            Cai Wain
            Senior Engineer, Acme AB
            2019 - present
            Built the ingestion pipeline
            """;

    private ExtractedProfile canned(String employer) {
        return new ExtractedProfile("Cai Wain", "Senior Engineer", null, null, null, null, "en",
                List.of(new ExtractedProfile.ExtractedExperience(
                        employer, "Senior Engineer", "2019", "present", true, null,
                        List.of("Built the ingestion pipeline"))),
                List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        profiles.deleteAll();
        documents.deleteAll();
        when(extractor.isAvailable()).thenReturn(true);
        when(extractor.modelName()).thenReturn("claude-opus-5");
        when(extractor.extract(anyString())).thenReturn(canned("Acme AB"));
    }

    private CvDocument storedDocument() {
        CvDocument doc = uploads.store("cv.pdf", "application/pdf", "pdf-bytes".getBytes());
        doc.setExtractedText(SOURCE);
        return documents.save(doc);
    }

    @Test
    void ingestPersistsAProfileNeedingReview() {
        CvProfile profile = profileService.ingest(storedDocument());

        assertThat(profile.getStatus()).isEqualTo(ProfileStatus.NEEDS_REVIEW);
        assertThat(profile.getFullName()).isEqualTo("Cai Wain");
        assertThat(profile.getExperiences()).hasSize(1);
        assertThat(profile.getExperiences().getFirst().getBullets()).hasSize(1);
        assertThat(profile.getModelUsed()).isEqualTo("claude-opus-5");
    }

    @Test
    void verifiedExperienceIsNotFlagged() {
        CvProfile profile = profileService.ingest(storedDocument());
        assertThat(profile.getExperiences().getFirst().isVerified()).isTrue();
        assertThat(profile.getExperiences().getFirst().getVerificationNotes()).isNull();
    }

    @Test
    void inventedEmployerIsFlaggedUnverified() {
        when(extractor.extract(anyString())).thenReturn(canned("Globex Corporation"));

        CvProfile profile = profileService.ingest(storedDocument());

        assertThat(profile.getExperiences().getFirst().isVerified()).isFalse();
        assertThat(profile.getExperiences().getFirst().getVerificationNotes())
                .contains("Globex Corporation");
    }

    @Test
    void extractionFailureIsRecordedAndRecoverable() {
        when(extractor.extract(anyString()))
                .thenThrow(new CvProfileExtractor.ExtractionFailedException("boom", new RuntimeException()));

        CvProfile profile = profileService.ingest(storedDocument());
        assertThat(profile.getStatus()).isEqualTo(ProfileStatus.EXTRACTION_FAILED);

        // Raw text survives, so re-extraction needs no re-upload.
        when(extractor.extract(anyString())).thenReturn(canned("Acme AB"));
        CvProfile retried = profileService.reextract();
        assertThat(retried.getStatus()).isEqualTo(ProfileStatus.NEEDS_REVIEW);
    }

    @Test
    void reUploadingTheSameBytesIsIdempotent() {
        CvDocument first = uploads.store("cv.pdf", "application/pdf", "identical".getBytes());
        CvDocument second = uploads.store("cv-copy.pdf", "application/pdf", "identical".getBytes());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(documents.count()).isEqualTo(1);
    }

    @Test
    void rejectsNonPdfAndOversizeUploads() {
        assertThatThrownBy(() -> uploads.store("cv.txt", "text/plain", "x".getBytes()))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("PDF");

        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> uploads.store("cv.pdf", "application/pdf", tooBig))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("large");
    }

    @Test
    void approveMovesProfileToReady() {
        profileService.ingest(storedDocument());

        CvProfile approved = profileService.approve();

        assertThat(approved.getStatus()).isEqualTo(ProfileStatus.READY);
        assertThat(approved.getReviewedAt()).isNotNull();
    }

    @Test
    void currentProfileIsTheNewest() {
        profileService.ingest(storedDocument());

        CvDocument another = uploads.store("cv2.pdf", "application/pdf", "different-bytes".getBytes());
        another.setExtractedText(SOURCE);
        documents.save(another);
        when(extractor.extract(anyString())).thenReturn(canned("Acme AB"));
        CvProfile newest = profileService.ingest(another);

        assertThat(profileService.current().orElseThrow().getId()).isEqualTo(newest.getId());
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvProfileServiceTest
```

Expected: compilation failure — the services do not exist.

- [ ] **Step 3: Write `UploadRejectedException` and `CvUploadService`**

```java
// profile/UploadRejectedException.java
package se.caiowain.jobseeker.profile;

/** The uploaded file is not something we accept. Maps to HTTP 400. */
public class UploadRejectedException extends RuntimeException {
    public UploadRejectedException(String message) {
        super(message);
    }
}
```

```java
// profile/CvUploadService.java
package se.caiowain.jobseeker.profile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class CvUploadService {

    private final CvDocumentRepository documents;
    private final long maxBytes;

    public CvUploadService(CvDocumentRepository documents,
                           @Value("${jobseeker.profile.max-upload-bytes:10485760}") long maxBytes) {
        this.documents = documents;
        this.maxBytes = maxBytes;
    }

    /**
     * Stores the upload, or returns the existing document when the same bytes were already
     * uploaded. Idempotency matters: an accidental double upload must not discard the
     * corrections already made against the first one.
     */
    @Transactional
    public CvDocument store(String filename, String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new UploadRejectedException("The uploaded file is empty");
        }
        if (content.length > maxBytes) {
            throw new UploadRejectedException(
                    "The file is too large: " + content.length + " bytes, limit is " + maxBytes);
        }
        boolean looksLikePdf = (contentType != null && contentType.toLowerCase().contains("pdf"))
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
        if (!looksLikePdf) {
            throw new UploadRejectedException("Only PDF uploads are accepted");
        }

        String hash = sha256(content);
        return documents.findBySha256(hash).orElseGet(() -> {
            CvDocument document = new CvDocument();
            document.setFilename(filename == null ? "cv.pdf" : filename);
            document.setContentType(contentType == null ? "application/pdf" : contentType);
            document.setSizeBytes(content.length);
            document.setSha256(hash);
            document.setContent(content);
            document.setUploadedAt(Instant.now());
            return documents.save(document);
        });
    }

    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String utf8(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Write `CvProfileService`**

```java
package se.caiowain.jobseeker.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.extract.CvProfileExtractor;
import se.caiowain.jobseeker.profile.extract.ExtractedProfile;
import se.caiowain.jobseeker.profile.extract.ExtractionValidator;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CvProfileService {

    private static final Logger log = LoggerFactory.getLogger(CvProfileService.class);

    private final CvProfileRepository profiles;
    private final CvDocumentRepository documents;
    private final CvProfileExtractor extractor;
    private final ExtractionValidator validator;

    public CvProfileService(CvProfileRepository profiles, CvDocumentRepository documents,
                            CvProfileExtractor extractor, ExtractionValidator validator) {
        this.profiles = profiles;
        this.documents = documents;
        this.extractor = extractor;
        this.validator = validator;
    }

    public Optional<CvProfile> current() {
        return profiles.findFirstByOrderByIdDesc();
    }

    /** Extract, validate, persist. A failed model call is recorded, never thrown away. */
    @Transactional
    public CvProfile ingest(CvDocument document) {
        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setExtractedAt(Instant.now());
        profile.setModelUsed(extractor.modelName());

        try {
            ExtractedProfile extracted = extractor.extract(document.getExtractedText());
            apply(profile, extracted, document.getExtractedText());
            profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        } catch (CvProfileExtractor.ExtractionFailedException e) {
            log.warn("Extraction failed for document {}: {}", document.getId(), e.getMessage());
            profile.setStatus(ProfileStatus.EXTRACTION_FAILED);
        }
        return profiles.save(profile);
    }

    /** Re-runs extraction from the stored text — no re-upload, no second PDF parse. */
    @Transactional
    public CvProfile reextract() {
        CvProfile existing = current().orElseThrow(
                () -> new IllegalStateException("There is no profile to re-extract"));
        CvDocument document = existing.getCvDocument();
        return ingest(documents.findById(document.getId()).orElse(document));
    }

    @Transactional
    public CvProfile approve() {
        CvProfile profile = current().orElseThrow(
                () -> new IllegalStateException("There is no profile to approve"));
        profile.setStatus(ProfileStatus.READY);
        profile.setReviewedAt(Instant.now());
        return profiles.save(profile);
    }

    /** Replaces the editable content of the current profile with the reviewer's version. */
    @Transactional
    public CvProfile applyCorrections(CvProfile edited) {
        CvProfile profile = current().orElseThrow(
                () -> new IllegalStateException("There is no profile to update"));

        profile.setFullName(edited.getFullName());
        profile.setHeadline(edited.getHeadline());
        profile.setEmail(edited.getEmail());
        profile.setPhone(edited.getPhone());
        profile.setLocation(edited.getLocation());
        profile.setSummary(edited.getSummary());
        profile.setLanguage(edited.getLanguage());

        profile.getExperiences().clear();
        for (CvExperience e : edited.getExperiences()) {
            CvExperience copy = new CvExperience();
            copy.setEmployer(e.getEmployer());
            copy.setTitle(e.getTitle());
            copy.setStartDate(e.getStartDate());
            copy.setEndDate(e.getEndDate());
            copy.setCurrent(e.isCurrent());
            copy.setLocation(e.getLocation());
            copy.setOrdinal(e.getOrdinal());
            copy.setVerified(e.isVerified());
            copy.setVerificationNotes(e.getVerificationNotes());
            profile.addExperience(copy);
            for (CvExperienceBullet b : e.getBullets()) {
                CvExperienceBullet bullet = new CvExperienceBullet();
                bullet.setText(b.getText());
                bullet.setOrdinal(b.getOrdinal());
                copy.addBullet(bullet);
            }
        }

        profile.getEducation().clear();
        for (CvEducation e : edited.getEducation()) {
            CvEducation copy = new CvEducation();
            copy.setInstitution(e.getInstitution());
            copy.setDegree(e.getDegree());
            copy.setFieldOfStudy(e.getFieldOfStudy());
            copy.setStartDate(e.getStartDate());
            copy.setEndDate(e.getEndDate());
            copy.setOrdinal(e.getOrdinal());
            copy.setVerified(e.isVerified());
            copy.setVerificationNotes(e.getVerificationNotes());
            profile.addEducation(copy);
        }

        profile.getSkills().clear();
        for (CvSkill s : edited.getSkills()) {
            CvSkill copy = new CvSkill();
            copy.setName(s.getName());
            copy.setCategory(s.getCategory());
            copy.setOrdinal(s.getOrdinal());
            profile.addSkill(copy);
        }

        return profiles.save(profile);
    }

    /** Maps the model's output onto entities, attaching the validator's findings. */
    private void apply(CvProfile profile, ExtractedProfile extracted, String sourceText) {
        profile.setFullName(extracted.fullName());
        profile.setHeadline(extracted.headline());
        profile.setEmail(extracted.email());
        profile.setPhone(extracted.phone());
        profile.setLocation(extracted.location());
        profile.setSummary(extracted.summary());
        profile.setLanguage(extracted.language());

        var report = validator.verify(extracted, sourceText);

        List<ExtractedProfile.ExtractedExperience> experiences =
                extracted.experiences() == null ? List.of() : extracted.experiences();
        for (int i = 0; i < experiences.size(); i++) {
            var source = experiences.get(i);
            CvExperience experience = new CvExperience();
            experience.setEmployer(source.employer());
            experience.setTitle(source.title());
            experience.setStartDate(source.startDate());
            experience.setEndDate(source.endDate());
            experience.setCurrent(source.current());
            experience.setLocation(source.location());
            experience.setOrdinal(i);
            report.notesForExperience(i).ifPresentOrElse(note -> {
                experience.setVerified(false);
                experience.setVerificationNotes(note);
            }, () -> experience.setVerified(true));
            profile.addExperience(experience);

            List<String> bullets = source.bullets() == null ? List.of() : source.bullets();
            for (int b = 0; b < bullets.size(); b++) {
                CvExperienceBullet bullet = new CvExperienceBullet();
                bullet.setText(bullets.get(b));
                bullet.setOrdinal(b);
                experience.addBullet(bullet);
            }
        }

        List<ExtractedProfile.ExtractedEducation> education =
                extracted.education() == null ? List.of() : extracted.education();
        for (int i = 0; i < education.size(); i++) {
            var source = education.get(i);
            CvEducation entry = new CvEducation();
            entry.setInstitution(source.institution());
            entry.setDegree(source.degree());
            entry.setFieldOfStudy(source.fieldOfStudy());
            entry.setStartDate(source.startDate());
            entry.setEndDate(source.endDate());
            entry.setOrdinal(i);
            report.notesForEducation(i).ifPresentOrElse(note -> {
                entry.setVerified(false);
                entry.setVerificationNotes(note);
            }, () -> entry.setVerified(true));
            profile.addEducation(entry);
        }

        List<ExtractedProfile.ExtractedSkill> skills =
                extracted.skills() == null ? List.of() : extracted.skills();
        for (int i = 0; i < skills.size(); i++) {
            CvSkill skill = new CvSkill();
            skill.setName(skills.get(i).name());
            skill.setCategory(skills.get(i).category());
            skill.setOrdinal(i);
            profile.addSkill(skill);
        }
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=CvProfileServiceTest
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: add CV upload and profile services with verification wiring"
```

---

## Task 7: The profile REST API

**Files:**
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/api/ProfileController.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/api/dto/{ProfileDto,ExperienceDto,BulletDto,EducationDto,SkillDto}.java`
- Create: `backend/src/main/java/se/caiowain/jobseeker/profile/api/ProfileExceptionHandler.java`
- Test: `backend/src/test/java/se/caiowain/jobseeker/profile/api/ProfileControllerTest.java`

**Interfaces:**
- Consumes: `CvUploadService`, `CvProfileService`, `PdfTextExtractor`, `CvProfileExtractor`
- Produces: the six endpoints from the spec. `POST /upload` returns `201` for a new document and `200` for a repeat of bytes already stored — never re-extracting.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/se/caiowain/jobseeker/profile/api/ProfileControllerTest.java`:

```java
package se.caiowain.jobseeker.profile.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.profile.extract.CvProfileExtractor;
import se.caiowain.jobseeker.profile.extract.ExtractedProfile;
import se.caiowain.jobseeker.profile.extract.PdfTextExtractor;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean CvProfileExtractor extractor;
    @MockitoBean PdfTextExtractor pdfTextExtractor;

    private static final String SOURCE = "Cai Wain\nSenior Engineer, Acme AB\n2019 - present\n";

    @BeforeEach
    void setUp() {
        profiles.deleteAll();
        documents.deleteAll();
        when(pdfTextExtractor.extract(any())).thenReturn(SOURCE);
        when(extractor.isAvailable()).thenReturn(true);
        when(extractor.modelName()).thenReturn("claude-opus-5");
        when(extractor.extract(anyString())).thenReturn(new ExtractedProfile(
                "Cai Wain", "Senior Engineer", null, null, null, null, "en",
                List.of(new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2019", "present", true, null,
                        List.of("Built the ingestion pipeline"))),
                List.of(), List.of()));
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    }

    @Test
    void returns404WhenNoProfileExists() throws Exception {
        mvc.perform(get("/api/profile")).andExpect(status().isNotFound());
    }

    @Test
    void uploadExtractsAndReturnsTheProfile() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Cai Wain"))
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.experiences[0].employer").value("Acme AB"))
                .andExpect(jsonPath("$.experiences[0].bullets[0].text").value("Built the ingestion pipeline"))
                .andExpect(jsonPath("$.experiences[0].verified").value(true));
    }

    @Test
    void rejectsNonPdfWith400() throws Exception {
        mvc.perform(multipart("/api/profile/upload")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns503WhenNoApiKeyIsConfigured() throws Exception {
        when(extractor.isAvailable()).thenReturn(false);
        when(extractor.extract(anyString()))
                .thenThrow(new se.caiowain.jobseeker.profile.extract.ExtractionUnavailableException(
                        "CV extraction needs a model credential. Set ANTHROPIC_API_KEY and restart."));

        mvc.perform(multipart("/api/profile/upload").file(pdf()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void servesTheRawSourceTextForSideBySideReview() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(get("/api/profile/source-text"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Senior Engineer, Acme AB")));
    }

    @Test
    void savesCorrections() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(put("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Cai W.","headline":"Staff Engineer","language":"en",
                                 "experiences":[{"employer":"Acme AB","title":"Staff Engineer",
                                   "startDate":"2019","endDate":"present","current":true,"ordinal":0,
                                   "verified":true,"bullets":[{"text":"Rewrote the pipeline","ordinal":0}]}],
                                 "education":[],"skills":[{"name":"Java","ordinal":0}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Cai W."))
                .andExpect(jsonPath("$.experiences[0].title").value("Staff Engineer"))
                .andExpect(jsonPath("$.skills[0].name").value("Java"));
    }

    @Test
    void reUploadingTheSameFileReturns200AndDoesNotExtractAgain() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        // Same bytes again: 200 rather than 201, and no second model call.
        mvc.perform(multipart("/api/profile/upload").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Cai Wain"));

        org.mockito.Mockito.verify(extractor, org.mockito.Mockito.times(1)).extract(anyString());
        org.assertj.core.api.Assertions.assertThat(documents.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(profiles.count()).isEqualTo(1);
    }

    @Test
    void approveSetsReady() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(post("/api/profile/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void reextractRunsAgainFromStoredText() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(post("/api/profile/reextract"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"));
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=ProfileControllerTest
```

Expected: 404s / compilation failure — the controller does not exist.

- [ ] **Step 3: Write the DTOs**

```java
// profile/api/dto/BulletDto.java
package se.caiowain.jobseeker.profile.api.dto;

public record BulletDto(Long id, String text, int ordinal) {
}
```

```java
// profile/api/dto/ExperienceDto.java
package se.caiowain.jobseeker.profile.api.dto;

import java.util.List;

public record ExperienceDto(Long id, String employer, String title, String startDate,
                            String endDate, boolean current, String location, int ordinal,
                            boolean verified, String verificationNotes, List<BulletDto> bullets) {
}
```

```java
// profile/api/dto/EducationDto.java
package se.caiowain.jobseeker.profile.api.dto;

public record EducationDto(Long id, String institution, String degree, String fieldOfStudy,
                           String startDate, String endDate, int ordinal,
                           boolean verified, String verificationNotes) {
}
```

```java
// profile/api/dto/SkillDto.java
package se.caiowain.jobseeker.profile.api.dto;

public record SkillDto(Long id, String name, String category, int ordinal) {
}
```

```java
// profile/api/dto/ProfileDto.java
package se.caiowain.jobseeker.profile.api.dto;

import java.time.Instant;
import java.util.List;

public record ProfileDto(Long id, String fullName, String headline, String email, String phone,
                         String location, String summary, String language, String status,
                         String modelUsed, Instant extractedAt, Instant reviewedAt,
                         String sourceFilename,
                         List<ExperienceDto> experiences, List<EducationDto> education,
                         List<SkillDto> skills) {
}
```

- [ ] **Step 4: Write the exception handler**

```java
package se.caiowain.jobseeker.profile.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import se.caiowain.jobseeker.profile.UploadRejectedException;
import se.caiowain.jobseeker.profile.extract.ExtractionUnavailableException;
import se.caiowain.jobseeker.profile.extract.PdfTextExtractionException;

@RestControllerAdvice
public class ProfileExceptionHandler {

    @ExceptionHandler(UploadRejectedException.class)
    ProblemDetail rejected(UploadRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A scanned PDF is a valid file we cannot use — 422, not 400. */
    @ExceptionHandler(PdfTextExtractionException.class)
    ProblemDetail unreadable(PdfTextExtractionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ExtractionUnavailableException.class)
    ProblemDetail unavailable(ExtractionUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
```

- [ ] **Step 5: Write `ProfileController`**

```java
package se.caiowain.jobseeker.profile.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import se.caiowain.jobseeker.profile.CvProfileService;
import se.caiowain.jobseeker.profile.CvUploadService;
import se.caiowain.jobseeker.profile.UploadRejectedException;
import se.caiowain.jobseeker.profile.api.dto.*;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.extract.PdfTextExtractor;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final CvUploadService uploads;
    private final CvProfileService profileService;
    private final PdfTextExtractor pdfTextExtractor;
    private final CvDocumentRepository documents;
    private final CvProfileRepository profiles;

    public ProfileController(CvUploadService uploads, CvProfileService profileService,
                             PdfTextExtractor pdfTextExtractor, CvDocumentRepository documents,
                             CvProfileRepository profiles) {
        this.uploads = uploads;
        this.profileService = profileService;
        this.pdfTextExtractor = pdfTextExtractor;
        this.documents = documents;
        this.profiles = profiles;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileDto> upload(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UploadRejectedException("Could not read the uploaded file");
        }

        CvDocument document = uploads.store(file.getOriginalFilename(), file.getContentType(), content);

        // Spec 5.1: re-uploading identical bytes must not re-extract. Beyond wasting a paid
        // model call, a second extraction would replace corrections already made against the
        // first one. Return the existing profile with 200 instead of 201.
        var existing = profiles.findFirstByCvDocumentIdOrderByIdDesc(document.getId());
        if (existing.isPresent()) {
            return ResponseEntity.ok(toDto(existing.get()));
        }

        if (document.getExtractedText() == null || document.getExtractedText().isBlank()) {
            document.setExtractedText(pdfTextExtractor.extract(content));
            document = documents.save(document);
        }

        CvProfile profile = profileService.ingest(document);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(profile));
    }

    @GetMapping
    public ResponseEntity<ProfileDto> current() {
        return profileService.current()
                .map(p -> ResponseEntity.ok(toDto(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/source-text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sourceText() {
        return profileService.current()
                .map(p -> ResponseEntity.ok(p.getCvDocument().getExtractedText()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping
    public ProfileDto save(@RequestBody ProfileDto request) {
        return toDto(profileService.applyCorrections(fromDto(request)));
    }

    @PostMapping("/reextract")
    public ProfileDto reextract() {
        return toDto(profileService.reextract());
    }

    @PostMapping("/approve")
    public ProfileDto approve() {
        return toDto(profileService.approve());
    }

    private static ProfileDto toDto(CvProfile p) {
        List<ExperienceDto> experiences = p.getExperiences().stream()
                .map(e -> new ExperienceDto(e.getId(), e.getEmployer(), e.getTitle(),
                        e.getStartDate(), e.getEndDate(), e.isCurrent(), e.getLocation(),
                        e.getOrdinal(), e.isVerified(), e.getVerificationNotes(),
                        e.getBullets().stream()
                                .map(b -> new BulletDto(b.getId(), b.getText(), b.getOrdinal()))
                                .toList()))
                .toList();

        List<EducationDto> education = p.getEducation().stream()
                .map(e -> new EducationDto(e.getId(), e.getInstitution(), e.getDegree(),
                        e.getFieldOfStudy(), e.getStartDate(), e.getEndDate(), e.getOrdinal(),
                        e.isVerified(), e.getVerificationNotes()))
                .toList();

        List<SkillDto> skills = p.getSkills().stream()
                .map(s -> new SkillDto(s.getId(), s.getName(), s.getCategory(), s.getOrdinal()))
                .toList();

        return new ProfileDto(p.getId(), p.getFullName(), p.getHeadline(), p.getEmail(),
                p.getPhone(), p.getLocation(), p.getSummary(), p.getLanguage(),
                p.getStatus().name(), p.getModelUsed(), p.getExtractedAt(), p.getReviewedAt(),
                p.getCvDocument() == null ? null : p.getCvDocument().getFilename(),
                experiences, education, skills);
    }

    /** Builds a detached profile carrying the reviewer's edits, for the service to apply. */
    private static CvProfile fromDto(ProfileDto dto) {
        CvProfile profile = new CvProfile();
        profile.setFullName(dto.fullName());
        profile.setHeadline(dto.headline());
        profile.setEmail(dto.email());
        profile.setPhone(dto.phone());
        profile.setLocation(dto.location());
        profile.setSummary(dto.summary());
        profile.setLanguage(dto.language());

        if (dto.experiences() != null) {
            for (ExperienceDto e : dto.experiences()) {
                CvExperience experience = new CvExperience();
                experience.setEmployer(e.employer());
                experience.setTitle(e.title());
                experience.setStartDate(e.startDate());
                experience.setEndDate(e.endDate());
                experience.setCurrent(e.current());
                experience.setLocation(e.location());
                experience.setOrdinal(e.ordinal());
                experience.setVerified(e.verified());
                experience.setVerificationNotes(e.verificationNotes());
                profile.addExperience(experience);
                if (e.bullets() != null) {
                    for (BulletDto b : e.bullets()) {
                        CvExperienceBullet bullet = new CvExperienceBullet();
                        bullet.setText(b.text());
                        bullet.setOrdinal(b.ordinal());
                        experience.addBullet(bullet);
                    }
                }
            }
        }
        if (dto.education() != null) {
            for (EducationDto e : dto.education()) {
                CvEducation entry = new CvEducation();
                entry.setInstitution(e.institution());
                entry.setDegree(e.degree());
                entry.setFieldOfStudy(e.fieldOfStudy());
                entry.setStartDate(e.startDate());
                entry.setEndDate(e.endDate());
                entry.setOrdinal(e.ordinal());
                entry.setVerified(e.verified());
                entry.setVerificationNotes(e.verificationNotes());
                profile.addEducation(entry);
            }
        }
        if (dto.skills() != null) {
            for (SkillDto s : dto.skills()) {
                CvSkill skill = new CvSkill();
                skill.setName(s.name());
                skill.setCategory(s.category());
                skill.setOrdinal(s.ordinal());
                profile.addSkill(skill);
            }
        }
        return profile;
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test -Dtest=ProfileControllerTest
```

Expected: PASS, 9 tests.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
cd /home/cai/Projects/AgenticJobSeeker
git add backend/src
git commit -m "feat: add profile REST API with typed error responses"
```

---

## Task 8: Frontend types, API client and navigation

**Files:**
- Create: `frontend/src/profileTypes.ts`, `frontend/src/api/profileClient.ts`
- Modify: `frontend/src/App.tsx`, `frontend/src/pages/JobList.tsx`

**Interfaces:**
- Consumes: the REST API from Task 7
- Produces: `fetchProfile`, `uploadCv`, `saveProfile`, `reextractProfile`, `approveProfile`, `fetchSourceText`; route `/profile`

- [ ] **Step 1: Write the types**

`frontend/src/profileTypes.ts`:

```ts
export type ProfileStatus = 'NEEDS_REVIEW' | 'READY' | 'EXTRACTION_FAILED'

export interface Bullet {
  id?: number
  text: string
  ordinal: number
}

export interface Experience {
  id?: number
  employer: string | null
  title: string | null
  startDate: string | null
  endDate: string | null
  current: boolean
  location: string | null
  ordinal: number
  verified: boolean
  verificationNotes: string | null
  bullets: Bullet[]
}

export interface Education {
  id?: number
  institution: string | null
  degree: string | null
  fieldOfStudy: string | null
  startDate: string | null
  endDate: string | null
  ordinal: number
  verified: boolean
  verificationNotes: string | null
}

export interface Skill {
  id?: number
  name: string
  category: string | null
  ordinal: number
}

export interface Profile {
  id: number
  fullName: string | null
  headline: string | null
  email: string | null
  phone: string | null
  location: string | null
  summary: string | null
  language: string | null
  status: ProfileStatus
  modelUsed: string | null
  extractedAt: string | null
  reviewedAt: string | null
  sourceFilename: string | null
  experiences: Experience[]
  education: Education[]
  skills: Skill[]
}
```

- [ ] **Step 2: Write the API client**

`frontend/src/api/profileClient.ts`:

```ts
import type { Profile } from '../profileTypes'

/** Surfaces the server's ProblemDetail message rather than a bare status code. */
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

/** Resolves to null when no profile exists yet — a 404 here is expected, not an error. */
export async function fetchProfile(): Promise<Profile | null> {
  const response = await fetch('/api/profile')
  if (response.status === 404) return null
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<Profile>
}

export async function uploadCv(file: File): Promise<Profile> {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/api/profile/upload', { method: 'POST', body: form })
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<Profile>
}

export function saveProfile(profile: Profile): Promise<Profile> {
  return json<Profile>('/api/profile', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  })
}

export function reextractProfile(): Promise<Profile> {
  return json<Profile>('/api/profile/reextract', { method: 'POST' })
}

export function approveProfile(): Promise<Profile> {
  return json<Profile>('/api/profile/approve', { method: 'POST' })
}

export async function fetchSourceText(): Promise<string> {
  const response = await fetch('/api/profile/source-text')
  if (!response.ok) throw new Error(await readError(response))
  return response.text()
}
```

- [ ] **Step 3: Add the route**

`frontend/src/App.tsx` — replace the whole file:

```tsx
import { BrowserRouter, Route, Routes } from 'react-router-dom'
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
      </Routes>
    </BrowserRouter>
  )
}
```

- [ ] **Step 4: Add the nav link on the job list**

In `frontend/src/pages/JobList.tsx`, add the import:

```tsx
import { Link } from 'react-router-dom'
```

(it is already imported — leave it as is), and insert this directly above `<StatsHeader …>`:

```tsx
      <nav className="mb-4 flex gap-4 text-sm">
        <span className="font-medium text-slate-900">Jobs</span>
        <Link to="/profile" className="text-slate-600 hover:underline">
          My CV profile
        </Link>
      </nav>
```

- [ ] **Step 5: Commit (build is verified in Task 9, which adds `ProfilePage`)**

```bash
git add frontend/src
git commit -m "feat: add profile types, API client and navigation"
```

---

## Task 9: The profile page — upload and side-by-side review

**Files:**
- Create: `frontend/src/components/ProfileUpload.tsx`, `frontend/src/components/ProfileReview.tsx`, `frontend/src/pages/ProfilePage.tsx`

**Interfaces:**
- Consumes: the client from Task 8
- Produces: the `/profile` screen

- [ ] **Step 1: Write `ProfileUpload`**

`frontend/src/components/ProfileUpload.tsx`:

```tsx
import { useRef, useState } from 'react'
import { uploadCv } from '../api/profileClient'
import type { Profile } from '../profileTypes'

export function ProfileUpload({ onUploaded }: { onUploaded: (p: Profile) => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const send = async (file: File) => {
    setBusy(true)
    setError(null)
    try {
      onUploaded(await uploadCv(file))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rounded-lg border-2 border-dashed border-slate-300 p-10 text-center">
      <h2 className="text-lg font-medium text-slate-900">Upload your CV</h2>
      <p className="mt-1 text-sm text-slate-600">
        A PDF, up to 10 MB. It is read once and structured for you to review.
      </p>

      <input
        ref={input}
        type="file"
        accept="application/pdf"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void send(file)
        }}
      />

      <button
        onClick={() => input.current?.click()}
        disabled={busy}
        className="mt-5 rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-50"
      >
        {busy ? 'Extracting…' : 'Choose PDF'}
      </button>

      {busy && (
        <p className="mt-3 text-xs text-slate-500">
          Reading the PDF and structuring it. This takes a few seconds.
        </p>
      )}
      {error && <p className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    </div>
  )
}
```

- [ ] **Step 2: Write `ProfileReview`**

`frontend/src/components/ProfileReview.tsx`:

```tsx
import type { Experience, Profile } from '../profileTypes'

interface Props {
  profile: Profile
  sourceText: string
  onChange: (next: Profile) => void
}

function Field({ label, value, onChange }: {
  label: string
  value: string | null
  onChange: (v: string) => void
}) {
  return (
    <label className="block">
      <span className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</span>
      <input
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900"
      />
    </label>
  )
}

export function ProfileReview({ profile, sourceText, onChange }: Props) {
  const setExperience = (index: number, patch: Partial<Experience>) => {
    const experiences = profile.experiences.map((e, i) => (i === index ? { ...e, ...patch } : e))
    onChange({ ...profile, experiences })
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <section>
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Text read from your PDF
        </h2>
        <pre className="max-h-[70vh] overflow-auto whitespace-pre-wrap rounded-md bg-slate-50 p-3 font-mono text-xs leading-relaxed text-slate-700">
          {sourceText || 'No source text available.'}
        </pre>
      </section>

      <section className="space-y-6">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Full name" value={profile.fullName} onChange={(v) => onChange({ ...profile, fullName: v })} />
          <Field label="Headline" value={profile.headline} onChange={(v) => onChange({ ...profile, headline: v })} />
          <Field label="Email" value={profile.email} onChange={(v) => onChange({ ...profile, email: v })} />
          <Field label="Phone" value={profile.phone} onChange={(v) => onChange({ ...profile, phone: v })} />
          <Field label="Location" value={profile.location} onChange={(v) => onChange({ ...profile, location: v })} />
          <Field label="Language" value={profile.language} onChange={(v) => onChange({ ...profile, language: v })} />
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-900">Experience</h3>
          <div className="space-y-4">
            {profile.experiences.map((experience, index) => (
              <div
                key={experience.id ?? index}
                className={`rounded-md border p-3 ${
                  experience.verified ? 'border-slate-200' : 'border-amber-400 bg-amber-50'
                }`}
              >
                {!experience.verified && experience.verificationNotes && (
                  <p className="mb-2 text-xs font-medium text-amber-800">
                    {experience.verificationNotes}
                  </p>
                )}
                <div className="grid gap-2 sm:grid-cols-2">
                  <Field label="Employer" value={experience.employer}
                         onChange={(v) => setExperience(index, { employer: v })} />
                  <Field label="Title" value={experience.title}
                         onChange={(v) => setExperience(index, { title: v })} />
                  <Field label="From" value={experience.startDate}
                         onChange={(v) => setExperience(index, { startDate: v })} />
                  <Field label="To" value={experience.endDate}
                         onChange={(v) => setExperience(index, { endDate: v })} />
                </div>

                <div className="mt-3 space-y-2">
                  {experience.bullets.map((bullet, bulletIndex) => (
                    <div key={bullet.id ?? bulletIndex} className="flex gap-2">
                      <textarea
                        value={bullet.text}
                        rows={2}
                        onChange={(e) =>
                          setExperience(index, {
                            bullets: experience.bullets.map((b, i) =>
                              i === bulletIndex ? { ...b, text: e.target.value } : b,
                            ),
                          })
                        }
                        className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900"
                      />
                      <button
                        onClick={() =>
                          setExperience(index, {
                            bullets: experience.bullets
                              .filter((_, i) => i !== bulletIndex)
                              .map((b, i) => ({ ...b, ordinal: i })),
                          })
                        }
                        className="shrink-0 rounded-md border border-slate-300 px-2 text-xs text-slate-600 hover:bg-slate-50"
                        aria-label="Remove bullet"
                      >
                        Remove
                      </button>
                    </div>
                  ))}
                  <button
                    onClick={() =>
                      setExperience(index, {
                        bullets: [...experience.bullets, { text: '', ordinal: experience.bullets.length }],
                      })
                    }
                    className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50"
                  >
                    Add bullet
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-900">Education</h3>
          <div className="space-y-3">
            {profile.education.map((entry, index) => (
              <div
                key={entry.id ?? index}
                className={`rounded-md border p-3 ${
                  entry.verified ? 'border-slate-200' : 'border-amber-400 bg-amber-50'
                }`}
              >
                {!entry.verified && entry.verificationNotes && (
                  <p className="mb-2 text-xs font-medium text-amber-800">{entry.verificationNotes}</p>
                )}
                <div className="grid gap-2 sm:grid-cols-2">
                  <Field label="Institution" value={entry.institution}
                         onChange={(v) => onChange({
                           ...profile,
                           education: profile.education.map((x, i) =>
                             i === index ? { ...x, institution: v } : x),
                         })} />
                  <Field label="Degree" value={entry.degree}
                         onChange={(v) => onChange({
                           ...profile,
                           education: profile.education.map((x, i) =>
                             i === index ? { ...x, degree: v } : x),
                         })} />
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-900">Skills</h3>
          <input
            value={profile.skills.map((s) => s.name).join(', ')}
            onChange={(e) =>
              onChange({
                ...profile,
                skills: e.target.value
                  .split(',')
                  .map((s) => s.trim())
                  .filter(Boolean)
                  .map((name, i) => ({ name, category: null, ordinal: i })),
              })
            }
            className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900"
            placeholder="Comma-separated"
          />
        </div>
      </section>
    </div>
  )
}
```

- [ ] **Step 3: Write `ProfilePage`**

`frontend/src/pages/ProfilePage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  approveProfile, fetchProfile, fetchSourceText, reextractProfile, saveProfile,
} from '../api/profileClient'
import { ProfileReview } from '../components/ProfileReview'
import { ProfileUpload } from '../components/ProfileUpload'
import type { Profile } from '../profileTypes'

export function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [sourceText, setSourceText] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadSource = () => { fetchSourceText().then(setSourceText).catch(() => setSourceText('')) }

  useEffect(() => {
    fetchProfile()
      .then((p) => { setProfile(p); if (p) loadSource() })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const run = async (action: () => Promise<Profile>, note: string) => {
    setBusy(true); setError(null); setMessage(null)
    try {
      setProfile(await action())
      setMessage(note)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const unverified =
    (profile?.experiences.filter((e) => !e.verified).length ?? 0) +
    (profile?.education.filter((e) => !e.verified).length ?? 0)

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <span className="font-medium text-slate-900">My CV profile</span>
      </nav>

      {loading && <p className="text-sm text-slate-500">Loading…</p>}

      {!loading && !profile && (
        <ProfileUpload onUploaded={(p) => { setProfile(p); loadSource() }} />
      )}

      {profile && (
        <>
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
                {profile.fullName ?? 'Your CV profile'}
              </h1>
              <p className="mt-1 text-sm text-slate-600">
                {profile.status === 'READY' ? 'Approved' : 'Needs review'}
                {profile.sourceFilename ? ` · from ${profile.sourceFilename}` : ''}
                {profile.modelUsed ? ` · extracted by ${profile.modelUsed}` : ''}
              </p>
              {unverified > 0 && (
                <p className="mt-1 text-sm font-medium text-amber-700">
                  {unverified} entr{unverified === 1 ? 'y' : 'ies'} could not be found in your PDF —
                  check the highlighted fields.
                </p>
              )}
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => run(() => saveProfile(profile), 'Saved.')}
                disabled={busy}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
              >
                Save
              </button>
              <button
                onClick={() => run(async () => { const p = await reextractProfile(); loadSource(); return p }, 'Re-extracted.')}
                disabled={busy}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
              >
                Re-extract
              </button>
              <button
                onClick={() => run(() => approveProfile(), 'Profile approved.')}
                disabled={busy}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
              >
                Approve profile
              </button>
            </div>
          </header>

          {message && <p className="mb-4 rounded-md bg-emerald-50 p-3 text-sm text-emerald-800">{message}</p>}
          {error && <p className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

          {profile.status === 'EXTRACTION_FAILED' ? (
            <p className="rounded-md bg-amber-50 p-4 text-sm text-amber-800">
              Extraction did not complete. Your PDF and its text are stored — press
              <strong> Re-extract </strong> to try again.
            </p>
          ) : (
            <ProfileReview profile={profile} sourceText={sourceText} onChange={setProfile} />
          )}
        </>
      )}
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
git commit -m "feat: add CV profile upload and side-by-side review page"
```

---

## Task 10: End-to-end verification and documentation

**Files:**
- Modify: `README.md`
- Test: manual verification against the running system

**Interfaces:**
- Consumes: everything
- Produces: a verified running system and updated documentation

- [ ] **Step 1: Run the full backend suite**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw -B test
```

Expected: all tests PASS, with no network access.

- [ ] **Step 2: Verify the no-key path first**

```bash
cd /home/cai/Projects/AgenticJobSeeker
docker compose up -d
cd backend && export JAVA_HOME=/usr/lib/jvm/default && ./mvnw spring-boot:run
```

With `ANTHROPIC_API_KEY` unset, in a second shell:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health   # expect 200
curl -s "http://localhost:8080/api/jobs?size=1" | head -c 120                     # Slice 1 still works
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/profile        # expect 404
```

Expected: the application starts, Slice 1 is unaffected, and no profile exists. This is
success criterion 6.

- [ ] **Step 3: Verify the upload rejection paths**

```bash
printf 'not a pdf' > /tmp/cv.txt
curl -s -o /dev/null -w "non-pdf: %{http_code}\n" -F "file=@/tmp/cv.txt" http://localhost:8080/api/profile/upload
```

Expected: `400`.

- [ ] **Step 4: Verify the 503 path with no credential**

```bash
curl -s -w "\nno-key: %{http_code}\n" -F "file=@<a real CV>.pdf" http://localhost:8080/api/profile/upload
```

Expected: `503` with a message naming `ANTHROPIC_API_KEY`. If instead this returns `422`, the
PDF has no text layer — that is also a correct response, for a different reason.

- [ ] **Step 5: Verify the full path with a credential**

Restart the backend with a key exported, then:

```bash
curl -s -F "file=@<a real CV>.pdf" http://localhost:8080/api/profile/upload | python3 -m json.tool | head -40
curl -s http://localhost:8080/api/profile | python3 -c "
import json,sys; p=json.load(sys.stdin)
print('name       :', p['fullName'])
print('status     :', p['status'])
print('experience :', len(p['experiences']))
print('unverified :', sum(1 for e in p['experiences'] if not e['verified']))
"
```

Expected: a structured profile, `NEEDS_REVIEW`, and experience entries. Confirm in the
database that the raw text was retained:

```bash
docker exec jobseeker-postgres psql -U jobseeker -d job_db -t -A -c \
  "SELECT length(extracted_text) FROM cv_document ORDER BY id DESC LIMIT 1;"
```

- [ ] **Step 6: Verify idempotency and the review flow in the browser**

Upload the same file a second time — `cv_document` count must stay at 1. Then open
`http://localhost:5173/profile` and confirm: raw text renders on the left, the form on the
right, unverified rows are amber with their note, edits save, and Approve flips the status
to `READY`.

- [ ] **Step 7: Update the README**

Add to `README.md`, after the "Sources" section:

````markdown
## CV profile

Slice 2a ingests your CV so Slice 2b can tailor from it. Upload a PDF at
`http://localhost:5173/profile`; PDFBox extracts the text, Claude structures it, and a
validator checks that every extracted employer, title and date actually appears in your
PDF. Anything it cannot find is flagged amber for you to check rather than silently
trusted.

Extraction needs a model credential:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Without it the application still starts and job discovery works normally; CV upload
returns `503`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/profile/upload` | Upload a CV PDF and extract it |
| `GET` | `/api/profile` | Current profile |
| `PUT` | `/api/profile` | Save corrections |
| `POST` | `/api/profile/reextract` | Re-run extraction from stored text |
| `POST` | `/api/profile/approve` | Mark the profile reviewed |
| `GET` | `/api/profile/source-text` | Raw text read from the PDF |
````

Also update the "Not in this slice" section to read:

```markdown
## Not in this slice

Tailored CV and cover-letter generation, translation, PDF rendering and the approval queue
(Slice 2b); Playwright application submission (Slice 3); metrics and settings (Slice 4).
```

- [ ] **Step 8: Commit**

```bash
git add README.md
git commit -m "docs: document CV profile ingestion"
```

---

## Done criteria

1. `./mvnw test` passes with no live LLM or network calls.
2. With no API key, the application starts, Slice 1 works, and upload returns `503`.
3. A non-PDF upload returns `400`; a scanned PDF returns `422`.
4. With a key, uploading a real CV produces a structured profile in PostgreSQL.
5. Extracted employers, titles and dates absent from the source are flagged `unverified`.
6. Re-uploading identical bytes creates no second document and no second extraction.
7. The `/profile` page shows raw text and form side by side, with unverified rows in amber.
8. Corrections persist and Approve sets the profile to `READY`.
9. Re-extraction works from stored text without re-uploading.
