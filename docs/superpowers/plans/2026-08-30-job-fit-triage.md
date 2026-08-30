# Job Fit Triage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank every discovered posting against your CV profile, veto the ones you are not eligible for, and record what you decided — without asking a model for an opinion about you.

**Architecture:** Two stages. A **prescreen** runs entirely in Postgres: a generated `tsvector` column over each posting's title and description is matched against your `cv_skill` rows, producing a count of named skills the ad mentions, plus deterministic language, location and deadline gates. It covers the whole corpus in milliseconds and needs no model. A **deep score** then runs on demand for one job, reusing Slice 2b's selection call, guards and coverage formula unchanged. Neither stage emits a judgement: the prescreen counts facts, the deep stage measures coverage, and gates are vetoes that always quote the phrase that caused them.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-model-ollama`), Ollama `qwen2.5:7b-instruct`, PostgreSQL 16 full-text search, Flyway, Testcontainers, React 19 + Vite 8 + Tailwind 4.

**Spec:** `docs/superpowers/specs/2026-08-30-job-fit-triage-design.md`

## Global Constraints

Everything from Slices 1, 2a and 2b still applies. Repeated because they are easy to get wrong:

- **Spring Boot version is exactly `4.1.1`.** Never `4.1.1.RELEASE`.
- **Jackson 3** — import `tools.jackson.databind.*`, never `com.fasterxml.jackson.databind.*`. `JsonNode.asText()` is `asString()`.
- **`@AutoConfigureMockMvc` is `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.**
- **Mockito re-stubbing:** use `doReturn(...).when(mock).method(...)`, not `when(mock.method(...))`, when a method is already stubbed to throw.
- **Maven is not installed.** Build with `./mvnw` from `backend/` after `export JAVA_HOME=/usr/lib/jvm/default`.
- **PostgreSQL is on port 5433.** Tests use Testcontainers `postgres:16-alpine` via `AbstractIntegrationTest`.
- **No test may call Ollama or any network host.** `ChatClient` is stubbed in every test.
- **The full-text config is `'simple'`, never `'swedish'` or `'english'`.** Two reasons, both binding: a `GENERATED ALWAYS AS ... STORED` column requires an `IMMUTABLE` expression, so the config cannot be chosen from the row's `language`; and stemming actively damages skill terms — the `swedish` config mangles *Spring*, the `english` config mangles *React*. Do not "improve" this to a language-aware config.
- **Skill matching uses `phraseto_tsquery`, not `to_tsquery` or `plainto_tsquery`.** `plainto_tsquery('simple','Spring Boot')` ANDs the two lexemes anywhere in the document, so an ad mentioning *Spring* in one paragraph and *Boot* in another would falsely match. `phraseto_tsquery` requires them adjacent.
- **A prescreen run must be structurally incapable of destroying `job_triage` or `job_deep_fit` rows.** It truncates and rebuilds `job_prescreen` only. There is no foreign key from either preserved table to `job_prescreen`, and there must never be one.
- **UNKNOWN is not PASS.** A gate that cannot parse a requirement records `UNKNOWN`. Never default an unparsed requirement to a pass.
- **The model never emits candidate-facing prose.** This slice adds no new prompt and no new guard; it reuses Slice 2b's. Any change that lets model output flow into an employer-visible string is a design violation, not an improvement.
- **No code in this slice may read, navigate, follow or submit `apply_url`.** The constraint is satisfied structurally — there is no such code path to guard — and it must stay that way.
- **TDD:** failing test, watch it fail, minimal implementation, watch it pass, commit.
- Every task ends with a commit.

### One deviation from the spec, already decided

The spec keys `job_prescreen` on `(job_posting_id, cv_profile_id)`. This plan makes it **unique on `job_posting_id` alone**, with `cv_profile_id` kept as a stamp recording which profile produced the row.

The prescreen truncates and rebuilds the whole table for the current profile, so at most one row per posting can ever exist. Uniqueness on the posting alone makes that invariant enforced by the database rather than merely assumed, and it lets `JobPosting` carry a genuine `@OneToOne` inverse mapping — which is what makes sorting the job list by fit a left join rather than a second query.

`job_deep_fit` keeps the spec's `(job_posting_id, cv_profile_id)` key. There the two-column key is real: deep scores from a superseded profile are kept as history.

---

## File Structure

**Backend** (`backend/src/main/java/se/caiowain/jobseeker/`):

| Path | Responsibility |
|---|---|
| `select/SelectionPromptBuilder.java` | *(moved from `tailor/select/TailoringPromptBuilder.java`)* |
| `select/SelectionResult.java` | *(moved)* What the model returns |
| `select/NumberedBullet.java` | *(moved)* Prompt index ↔ profile bullet id |
| `select/SelectionGuard.java` | *(moved)* Guards A, B, C |
| `select/OllamaSelectionClient.java` | *(moved)* The only component that calls a model |
| `select/ModelUnavailableException.java` | *(moved from `tailor/TailoringUnavailableException.java`)* |
| `select/SelectionRejectedException.java` | *(moved from `tailor/TailoringRejectedException.java`)* |
| `profile/ProfileNotReadyException.java` | *(moved from `tailor/`)* A statement about a profile |
| `api/ApiExceptionHandler.java` | *(moved from `tailor/api/TailoringExceptionHandler.java`)* Application-wide advice; also covers fit |
| `select/Coverage.java` | **New.** Pure: the coverage formula, shared by tailoring and fit |
| `fit/domain/JobPreferences.java` | Singleton: what you will accept |
| `fit/domain/CandidateLanguage.java` | One language you speak, with a level |
| `fit/domain/LanguageLevel.java` | Ordered enum, so a gate can compare |
| `fit/domain/RemotePolicy.java` | Enum: ONSITE_ONLY, HYBRID_OK, REMOTE_ONLY |
| `fit/domain/GateVerdict.java` | Enum: PASS, FLAG, FAIL, UNKNOWN |
| `fit/domain/JobPrescreen.java` | Disposable: matched skills + gate verdicts per posting |
| `fit/domain/JobDeepFit.java` | Preserved: coverage from one 94-second run |
| `fit/domain/JobDeepFitGap.java` | Preserved: a requirement that matched no bullet |
| `fit/domain/JobTriage.java` | Preserved: your decision |
| `fit/domain/TriageState.java` | Enum: NEW, SHORTLISTED, DISMISSED |
| `fit/LanguageRequirement.java` | Pure record: language, level, the phrase that said so |
| `fit/LanguageRequirementDetector.java` | Pure: ad text → language requirements |
| `fit/GateEvaluator.java` | Pure: requirements + preferences + posting → verdicts |
| `fit/SkillMatchQuery.java` | The one native FTS query |
| `fit/PrescreenService.java` | Truncates and rebuilds `job_prescreen` |
| `fit/DeepFitService.java` | select → guard → Coverage → persist |
| `fit/TriageService.java` | State transitions |
| `fit/FitConfig.java` | Beans for the pure components |
| `fit/repo/*.java` | Four Spring Data repositories |
| `fit/api/FitController.java` | `/api/fit/prescreen`, `/api/jobs/{id}/fit`, `/api/jobs/{id}/triage` |
| `fit/api/PreferencesController.java` | `/api/preferences` |
| `fit/api/dto/*.java` | Request and response records |

**Modified:**

| Path | Change |
|---|---|
| `domain/JobPosting.java` | Two `@OneToOne(mappedBy=...)` inverse mappings. No new column. |
| `api/JobQueryService.java` | Left joins, fit sort, triage and gate filters |
| `api/JobController.java` | New query params, richer summary |
| `api/dto/JobSummaryDto.java` | Fit fields |
| `tailor/ApplicationAssembler.java` | Uses `Coverage`; imports move |
| `tailor/TailoringService.java` | Imports move |
| `tailor/TailoringConfig.java` | Imports move |
| `backend/src/main/resources/application.yml` | `jobseeker.tailor.max-bullets-per-requirement` → `jobseeker.select.…` |
| `api/IngestController.java` | Re-ranks after a manual ingest, best-effort |
| `ingest/IngestScheduler.java` | Re-ranks after a scheduled ingest, best-effort |

**Migration:** `backend/src/main/resources/db/migration/V7__job_fit.sql`

**Frontend** (`frontend/src/`):

| Path | Responsibility |
|---|---|
| `fitTypes.ts` | Preferences, prescreen, deep fit, triage types |
| `api/fitClient.ts` | Fit and preferences calls |
| `pages/PreferencesPage.tsx` | The hand-edited preferences form |
| `components/GateBadge.tsx` | PASS / FLAG / FAIL / UNKNOWN pill with its quoted phrase |
| `components/SkillMatchChip.tsx` | `8 / 22 skills`, names on hover |
| `components/TriageButtons.tsx` | Shortlist / dismiss |

**Modified:** `types.ts`, `api/client.ts`, `App.tsx`, `pages/JobList.tsx`, `components/JobFilters.tsx`, `pages/JobDetail.tsx`.

---

## Task 1: Give the shared kernel an honest home, and add `Coverage`

Slice 2b put extractive selection inside the tailoring package because tailoring was its only
consumer. This slice is a second consumer, and it sits *upstream* of tailoring — so `fit`
importing from `tailor` would read backwards.

Five things move, all for the same reason: they are shared by both consumers, so neither
consumer should own them.

| From | To | Why |
|---|---|---|
| `tailor/select/*` | `select/*` | The selection call has two consumers now |
| `tailor/TailoringUnavailableException` | `select/ModelUnavailableException` | It means "the model is unreachable", which is not a tailoring fact |
| `tailor/TailoringRejectedException` | `select/SelectionRejectedException` | It means "the guards rejected the model", which belongs with the guards |
| `tailor/ProfileNotReadyException` | `profile/ProfileNotReadyException` | It is a statement about a profile |
| `tailor/api/TailoringExceptionHandler` | `api/ApiExceptionHandler` | `@RestControllerAdvice` is application-wide; after this slice it handles fit responses too, and a second advice for the same types would be ambiguous |

The property `jobseeker.tailor.max-bullets-per-requirement` becomes
`jobseeker.select.max-bullets-per-requirement` for the same reason — it configures the guard,
and both services read it.

No logic changes anywhere. The existing 2b tests are the safety net.

**Files:**
- Move: `tailor/select/*.java` → `select/*.java` (5 main, 2 test), plus the four types in the table above
- Create: `select/Coverage.java`, `src/test/java/se/caiowain/jobseeker/select/CoverageTest.java`
- Modify: `tailor/ApplicationAssembler.java`, `tailor/TailoringService.java`, `tailor/TailoringConfig.java`, `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: package `se.caiowain.jobseeker.select` containing `SelectionPromptBuilder`, `SelectionResult`, `NumberedBullet`, `SelectionGuard`, `OllamaSelectionClient`, `ModelUnavailableException`, `SelectionRejectedException`, and `Coverage.percent(int withEvidence, int total) -> int`; `se.caiowain.jobseeker.profile.ProfileNotReadyException`; `se.caiowain.jobseeker.api.ApiExceptionHandler`.

- [ ] **Step 1: Move the files with `git mv`**

```bash
cd backend/src/main/java/se/caiowain/jobseeker
mkdir -p select
git mv tailor/select/NumberedBullet.java select/
git mv tailor/select/OllamaSelectionClient.java select/
git mv tailor/select/SelectionGuard.java select/
git mv tailor/select/SelectionResult.java select/
git mv tailor/select/TailoringPromptBuilder.java select/SelectionPromptBuilder.java
git mv tailor/TailoringUnavailableException.java select/ModelUnavailableException.java
git mv tailor/TailoringRejectedException.java select/SelectionRejectedException.java
git mv tailor/ProfileNotReadyException.java ../../../../../main/java/se/caiowain/jobseeker/profile/ProfileNotReadyException.java
git mv tailor/api/TailoringExceptionHandler.java api/ApiExceptionHandler.java
rmdir tailor/select

cd ../../../../../test/java/se/caiowain/jobseeker
mkdir -p select
git mv tailor/select/SelectionGuardTest.java select/
git mv tailor/select/TailoringPromptBuilderTest.java select/SelectionPromptBuilderTest.java
rmdir tailor/select
```

Note the awkward relative path for `ProfileNotReadyException`: the working directory is
already inside `se/caiowain/jobseeker`, so the target is simply `profile/` — use
`git mv tailor/ProfileNotReadyException.java profile/` if the long form is confusing. Both do
the same thing.

- [ ] **Step 2: Rewrite class names, then package paths**

Two passes, and **the order matters**: the second pass matches text the first one produces.

```bash
cd backend/src

# Pass 1 — class and property renames.
grep -rl 'tailor\.select\|TailoringPromptBuilder\|TailoringUnavailableException\|TailoringRejectedException\|TailoringExceptionHandler\|jobseeker\.tailor\.max-bullets' \
    --include='*.java' --include='*.yml' . \
  | xargs sed -i \
      -e 's/se\.caiowain\.jobseeker\.tailor\.select/se.caiowain.jobseeker.select/g' \
      -e 's/TailoringPromptBuilder/SelectionPromptBuilder/g' \
      -e 's/TailoringUnavailableException/ModelUnavailableException/g' \
      -e 's/TailoringRejectedException/SelectionRejectedException/g' \
      -e 's/TailoringExceptionHandler/ApiExceptionHandler/g' \
      -e 's/jobseeker\.tailor\.max-bullets-per-requirement/jobseeker.select.max-bullets-per-requirement/g'

# Pass 2 — the import paths of the four types that changed package.
grep -rl 'se\.caiowain\.jobseeker\.tailor\.\(ModelUnavailableException\|SelectionRejectedException\|ProfileNotReadyException\)' \
    --include='*.java' . \
  | xargs sed -i \
      -e 's/se\.caiowain\.jobseeker\.tailor\.ModelUnavailableException/se.caiowain.jobseeker.select.ModelUnavailableException/g' \
      -e 's/se\.caiowain\.jobseeker\.tailor\.SelectionRejectedException/se.caiowain.jobseeker.select.SelectionRejectedException/g' \
      -e 's/se\.caiowain\.jobseeker\.tailor\.ProfileNotReadyException/se.caiowain.jobseeker.profile.ProfileNotReadyException/g'
```

Now fix the `package` line inside each moved file — those still name the old package:

```bash
cd backend/src/main/java/se/caiowain/jobseeker
sed -i 's/^package se\.caiowain\.jobseeker\.tailor;/package se.caiowain.jobseeker.select;/' \
  select/ModelUnavailableException.java select/SelectionRejectedException.java
sed -i 's/^package se\.caiowain\.jobseeker\.tailor;/package se.caiowain.jobseeker.profile;/' \
  profile/ProfileNotReadyException.java
sed -i 's/^package se\.caiowain\.jobseeker\.tailor\.api;/package se.caiowain.jobseeker.api;/' \
  api/ApiExceptionHandler.java
```

- [ ] **Step 3: Fix the imports the move exposes**

Three classes referred to these types from their own package and so carried no import. Compile
and let the compiler list them:

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q -DskipTests compile
```

Add exactly these, and nothing else:

| File | Change |
|---|---|
| `select/OllamaSelectionClient.java` | **Delete** its `ModelUnavailableException` import — the class is now in the same package |
| `tailor/TailoringService.java` | Add `import se.caiowain.jobseeker.profile.ProfileNotReadyException;` and `import se.caiowain.jobseeker.select.SelectionRejectedException;` |
| `api/ApiExceptionHandler.java` | Add `import se.caiowain.jobseeker.tailor.ApplicationAlreadyApprovedException;` if the compiler asks — the handler still maps that tailoring-specific type |
| `profile/ProfileNotReadyException.java` | Widen its javadoc: it currently reads "Tailoring needs an approved CV profile", and fit scoring now throws it too. Make it "Work that reads the CV profile needs one that has been approved. Maps to HTTP 409." |

Then repeat for the test sources, which have the same same-package problem:

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q -DskipTests test-compile
```

Any test in `se.caiowain.jobseeker.tailor` that names `ProfileNotReadyException` or
`SelectionRejectedException` needs the two imports above. Add them where the compiler points.

- [ ] **Step 3b: Rename the property in `application.yml`**

Pass 1 above rewrote any reference in code. Confirm the YAML key moved too — it lives under
`jobseeker:` and must now read:

```yaml
  select:
    max-bullets-per-requirement: 3
  tailor:
    request-timeout-seconds: 300
```

If the sed left `max-bullets-per-requirement` under `tailor:`, move the line by hand. A
mismatched key does not fail the build — it silently falls back to the `:3` default in the
`@Value` annotations, which is the same number, so the tests would still pass and the drift
would go unnoticed. Check it by eye.

- [ ] **Step 4: Write the failing `Coverage` test**

Create `backend/src/test/java/se/caiowain/jobseeker/select/CoverageTest.java`:

```java
package se.caiowain.jobseeker.select;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageTest {

    @Test
    void noRequirementsIsZeroRatherThanADivideByZero() {
        assertThat(Coverage.percent(0, 0)).isZero();
    }

    @Test
    void everyRequirementCoveredIsOneHundred() {
        assertThat(Coverage.percent(5, 5)).isEqualTo(100);
    }

    @Test
    void nothingCoveredIsZero() {
        assertThat(Coverage.percent(0, 7)).isZero();
    }

    @Test
    void roundsToTheNearestWholePercent() {
        // 2/3 = 66.67 -> 67. Rounding, not truncation: truncation would report 66,
        // which reads as further from the job than the evidence actually is.
        assertThat(Coverage.percent(2, 3)).isEqualTo(67);
        assertThat(Coverage.percent(1, 3)).isEqualTo(33);
    }
}
```

- [ ] **Step 5: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=CoverageTest
```

Expected: compilation failure, `cannot find symbol: class Coverage`.

- [ ] **Step 6: Write `Coverage`**

Create `backend/src/main/java/se/caiowain/jobseeker/select/Coverage.java`:

```java
package se.caiowain.jobseeker.select;

/**
 * How much of a job ad the candidate's own bullets actually answer.
 *
 * <p>Shared by tailoring and fit scoring so the two cannot drift. This number is computed
 * in Java from counted rows — it is never produced by a model, and it is not a judgement
 * about the candidate. Requirements with no evidence are the useful half of it.
 */
public final class Coverage {

    private Coverage() {
    }

    public static int percent(int withEvidence, int total) {
        return total == 0 ? 0 : Math.round(withEvidence * 100f / total);
    }
}
```

- [ ] **Step 7: Point `ApplicationAssembler` at `Coverage`**

In `backend/src/main/java/se/caiowain/jobseeker/tailor/ApplicationAssembler.java`, replace the inline calculation:

```java
        application.setCoveragePercent(
                selections.isEmpty() ? 0 : Math.round(withEvidence * 100f / selections.size()));
```

with:

```java
        application.setCoveragePercent(Coverage.percent(withEvidence, selections.size()));
```

and add the import alongside the other `select` imports:

```java
import se.caiowain.jobseeker.select.Coverage;
```

- [ ] **Step 8: Run the whole suite — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
```

Expected: every Slice 1, 2a and 2b test still green. The move changed no behaviour, so a failure here is a mechanical error in Steps 1–3, not a design problem.

- [ ] **Step 9: Commit**

```bash
git add -A backend/src
git commit -m "refactor: give the shared selection kernel an honest home

Fit scoring is a second consumer of the same selection call and sits
upstream of tailoring, so tailor/select/ would have been an import in the
wrong direction. Moves the selection package, its two exceptions and the
profile-readiness exception to packages both consumers can depend on, and
promotes the exception handler to application-wide advice. Adds Coverage so
the two consumers cannot drift on the formula. No logic changes."
```

---

## Task 2: The `V7` schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__job_fit.sql`
- Test: `backend/src/test/java/se/caiowain/jobseeker/fit/FitSchemaMigrationTest.java`

**Interfaces:**
- Produces: tables `job_preferences`, `candidate_language`, `job_prescreen`, `job_deep_fit`, `job_deep_fit_gap`, `job_triage`; column `job_posting.search_tsv` with a GIN index.

- [ ] **Step 1: Write the failing migration test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/FitSchemaMigrationTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.caiowain.jobseeker.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FitSchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTheFitTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "job_preferences", "candidate_language",
                "job_prescreen", "job_deep_fit", "job_deep_fit_gap", "job_triage");
    }

    @Test
    void jobPostingGainsAGeneratedSearchVector() {
        String generated = jdbc.queryForObject("""
                select is_generated from information_schema.columns
                where table_name = 'job_posting' and column_name = 'search_tsv'
                """, String.class);

        assertThat(generated).isEqualTo("ALWAYS");
    }

    @Test
    void theSearchVectorIsIndexedForSearch() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_posting' and indexdef like '%gin%search_tsv%'
                """, Integer.class);

        assertThat(count).isGreaterThan(0);
    }

    @Test
    void thePreferencesSingletonIsSeeded() {
        Integer rows = jdbc.queryForObject("select count(*) from job_preferences", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void atMostOnePrescreenRowPerPosting() {
        // Enforced in the database, not assumed: the prescreen truncates and rebuilds for
        // one profile, so two rows for one posting would mean a bug had already happened.
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_prescreen' and indexdef like '%UNIQUE%job_posting_id%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void deepFitIsKeptPerProfileSoSupersededScoresSurviveAsHistory() {
        Integer count = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'job_deep_fit'
                  and indexdef like '%UNIQUE%job_posting_id%cv_profile_id%'
                """, Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void nothingPreservedDependsOnTheDisposableTable() {
        // The load-bearing constraint of the whole slice: a prescreen rebuild truncates
        // job_prescreen, so a foreign key pointing at it from a preserved table would
        // cascade away your triage decisions or a 94-second deep score.
        Integer references = jdbc.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name
                where tc.constraint_type = 'FOREIGN KEY'
                  and ccu.table_name = 'job_prescreen'
                """, Integer.class);

        assertThat(references).isZero();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=FitSchemaMigrationTest
```

Expected: FAIL — the tables do not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V7__job_fit.sql`:

```sql
-- Slice 2d — job fit triage.
--
-- Table names state their lifecycle, because the lifecycles are opposite:
--   job_prescreen  is truncated and rebuilt on every prescreen run
--   job_deep_fit   costs ~94 seconds per row and must survive
--   job_triage     is your judgement and must survive
-- Nothing preserved may hold a foreign key into job_prescreen.

-- What you will accept. A singleton: things you decide, not things your CV says,
-- so re-extracting a CV can never clobber them.
CREATE TABLE job_preferences (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    home_municipality        VARCHAR(128),
    -- Comma-separated plain names, matching search_criteria.municipality_names.
    acceptable_municipalities TEXT,
    remote_policy            VARCHAR(32) NOT NULL DEFAULT 'HYBRID_OK',
    deal_breakers            TEXT,
    updated_at               TIMESTAMPTZ NOT NULL
);

INSERT INTO job_preferences (id, remote_policy, updated_at)
VALUES (1, 'HYBRID_OK', now());

CREATE TABLE candidate_language (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_preferences_id BIGINT      NOT NULL REFERENCES job_preferences (id) ON DELETE CASCADE,
    language           VARCHAR(8)  NOT NULL,
    -- Ordered: NONE < BASIC < CONVERSATIONAL < PROFESSIONAL < FLUENT < NATIVE.
    -- An enum rather than free text because a deterministic gate needs to compare.
    level              VARCHAR(32) NOT NULL,
    ordinal            INTEGER     NOT NULL DEFAULT 0
);

-- DISPOSABLE. Truncated and rebuilt in full by every prescreen run.
CREATE TABLE job_prescreen (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_posting_id      BIGINT      NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    -- A stamp recording which profile produced this row, not part of its identity.
    cv_profile_id       BIGINT      NOT NULL REFERENCES cv_profile (id) ON DELETE CASCADE,
    matched_skill_count INTEGER     NOT NULL DEFAULT 0,
    -- Comma-separated skill names, for display. The count is what orders the list.
    matched_skills      TEXT,
    language_gate       VARCHAR(16) NOT NULL,
    -- The phrase from the ad that produced the verdict. A veto always shows its evidence.
    language_note       TEXT,
    location_gate       VARCHAR(16) NOT NULL,
    deadline_passed     BOOLEAN     NOT NULL DEFAULT FALSE,
    computed_at         TIMESTAMPTZ NOT NULL
);

-- PRESERVED. Roughly 94 seconds of local-model time per row.
CREATE TABLE job_deep_fit (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_posting_id   BIGINT      NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    cv_profile_id    BIGINT      NOT NULL REFERENCES cv_profile (id) ON DELETE CASCADE,
    coverage_percent INTEGER     NOT NULL DEFAULT 0,
    requirement_count INTEGER    NOT NULL DEFAULT 0,
    model_used       VARCHAR(64),
    deep_scored_at   TIMESTAMPTZ NOT NULL
);

-- PRESERVED. The requirements that matched nothing — the most useful output here.
CREATE TABLE job_deep_fit_gap (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_deep_fit_id BIGINT  NOT NULL REFERENCES job_deep_fit (id) ON DELETE CASCADE,
    text            TEXT    NOT NULL,
    ordinal         INTEGER NOT NULL DEFAULT 0
);

-- PRESERVED. Your decision. Never recomputed by anything.
CREATE TABLE job_triage (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    job_posting_id BIGINT      NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    state          VARCHAR(32) NOT NULL,
    decided_at     TIMESTAMPTZ NOT NULL,
    note           TEXT
);

-- 'simple', not 'swedish': a generated column needs an IMMUTABLE expression, so the
-- config cannot depend on the row's language. It is also the right config regardless —
-- skill terms are proper nouns, and stemming mangles Spring and React.
ALTER TABLE job_posting ADD COLUMN search_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, ''))
    ) STORED;

CREATE INDEX ix_job_posting_search_tsv ON job_posting USING GIN (search_tsv);

CREATE UNIQUE INDEX ux_job_prescreen_job ON job_prescreen (job_posting_id);
CREATE INDEX ix_job_prescreen_matches    ON job_prescreen (matched_skill_count DESC);
CREATE UNIQUE INDEX ux_job_deep_fit_job_profile
    ON job_deep_fit (job_posting_id, cv_profile_id);
CREATE INDEX ix_job_deep_fit_gap_fit     ON job_deep_fit_gap (job_deep_fit_id);
CREATE UNIQUE INDEX ux_job_triage_job    ON job_triage (job_posting_id);
CREATE INDEX ix_candidate_language_prefs ON candidate_language (job_preferences_id);
```

- [ ] **Step 4: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=FitSchemaMigrationTest
```

Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V7__job_fit.sql \
        backend/src/test/java/se/caiowain/jobseeker/fit/FitSchemaMigrationTest.java
git commit -m "feat: add V7 fit schema and the job_posting search vector"
```

---

## Task 3: Preferences domain and repository

**Files:**
- Create: `fit/domain/JobPreferences.java`, `fit/domain/CandidateLanguage.java`, `fit/domain/LanguageLevel.java`, `fit/domain/RemotePolicy.java`, `fit/repo/JobPreferencesRepository.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/PreferencesPersistenceTest.java`

**Interfaces:**
- Produces: `LanguageLevel` with `atLeast(LanguageLevel other) -> boolean`; `JobPreferences` with `getLanguages()`, `addLanguage(CandidateLanguage)`, `acceptableMunicipalityList() -> List<String>`; `JobPreferencesRepository.findSingleton() -> JobPreferences`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/PreferencesPersistenceTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PreferencesPersistenceTest extends AbstractIntegrationTest {

    @Autowired
    JobPreferencesRepository preferences;

    @Test
    void theMigrationSeedsASingletonSoTheFormAlwaysHasSomethingToEdit() {
        JobPreferences prefs = preferences.findSingleton();

        assertThat(prefs.getId()).isEqualTo(1L);
        assertThat(prefs.getRemotePolicy()).isEqualTo(RemotePolicy.HYBRID_OK);
    }

    @Test
    void languagesRoundTripWithTheirLevels() {
        JobPreferences prefs = preferences.findSingleton();
        CandidateLanguage swedish = new CandidateLanguage();
        swedish.setLanguage("sv");
        swedish.setLevel(LanguageLevel.CONVERSATIONAL);
        swedish.setOrdinal(0);
        prefs.addLanguage(swedish);

        preferences.saveAndFlush(prefs);

        JobPreferences reloaded = preferences.findSingleton();
        assertThat(reloaded.getLanguages()).hasSize(1);
        assertThat(reloaded.getLanguages().getFirst().getLevel())
                .isEqualTo(LanguageLevel.CONVERSATIONAL);
    }

    @Test
    void municipalitiesAreStoredCommaSeparatedAndReadBackAsAList() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.setAcceptableMunicipalities("Stockholm, Solna ,Sundbyberg");

        assertThat(prefs.acceptableMunicipalityList())
                .containsExactly("Stockholm", "Solna", "Sundbyberg");
    }

    @Test
    void anUnsetMunicipalityListIsEmptyRatherThanASingleBlankEntry() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.setAcceptableMunicipalities("");

        assertThat(prefs.acceptableMunicipalityList()).isEmpty();
    }

    @Test
    void levelsCompareInDeclaredOrder() {
        assertThat(LanguageLevel.FLUENT.atLeast(LanguageLevel.CONVERSATIONAL)).isTrue();
        assertThat(LanguageLevel.CONVERSATIONAL.atLeast(LanguageLevel.FLUENT)).isFalse();
        assertThat(LanguageLevel.NATIVE.atLeast(LanguageLevel.NATIVE)).isTrue();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PreferencesPersistenceTest
```

Expected: compilation failure — the `fit.domain` classes do not exist.

- [ ] **Step 3: Write the enums**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/LanguageLevel.java`:

```java
package se.caiowain.jobseeker.fit.domain;

/**
 * Declared in increasing order, and the order is load-bearing.
 *
 * <p>Real postings and real CVs describe language ability in incompatible vocabularies —
 * CEFR letters, LinkedIn buckets, plain words like "conversational". A human can reason
 * about that ambiguity; a deterministic gate cannot. So the levels a person selects are
 * a fixed ordered scale, and ad phrasings are mapped onto it explicitly.
 */
public enum LanguageLevel {
    NONE, BASIC, CONVERSATIONAL, PROFESSIONAL, FLUENT, NATIVE;

    public boolean atLeast(LanguageLevel required) {
        return compareTo(required) >= 0;
    }
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/RemotePolicy.java`:

```java
package se.caiowain.jobseeker.fit.domain;

public enum RemotePolicy {
    /** Must be within reach of the office; the municipality list is a hard filter. */
    ONSITE_ONLY,
    /** Same location rule as onsite — you still have to reach the office some days. */
    HYBRID_OK,
    /** Location is irrelevant, so the location gate always passes. */
    REMOTE_ONLY
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/GateVerdict.java`:

```java
package se.caiowain.jobseeker.fit.domain;

/**
 * A gate's answer.
 *
 * <p>{@link #UNKNOWN} is not a pass. Pattern lists are never complete, and a gate that
 * silently passes everything it failed to parse is worse than no gate — it converts an
 * absence of information into a reassurance.
 */
public enum GateVerdict {
    PASS, FLAG, FAIL, UNKNOWN
}
```

- [ ] **Step 4: Write the entities**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/CandidateLanguage.java`:

```java
package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "candidate_language")
public class CandidateLanguage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_preferences_id", nullable = false)
    private JobPreferences preferences;

    @Column(nullable = false, length = 8) private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LanguageLevel level = LanguageLevel.NONE;

    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public JobPreferences getPreferences() { return preferences; }
    public void setPreferences(JobPreferences preferences) { this.preferences = preferences; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public LanguageLevel getLevel() { return level; }
    public void setLevel(LanguageLevel level) { this.level = level; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/JobPreferences.java`:

```java
package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What you will accept. A singleton, seeded by V7 so the form always has a row to edit.
 *
 * <p>Deliberately separate from {@code CvProfile}: that records what a document says about
 * you and is rewritten whenever a CV is re-extracted. These are decisions, and an
 * extraction must never be able to overwrite them.
 */
@Entity
@Table(name = "job_preferences")
public class JobPreferences {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "home_municipality", length = 128) private String homeMunicipality;

    /** Comma-separated plain names, matching {@code search_criteria.municipality_names}. */
    @Column(name = "acceptable_municipalities", columnDefinition = "text")
    private String acceptableMunicipalities;

    @Enumerated(EnumType.STRING)
    @Column(name = "remote_policy", nullable = false, length = 32)
    private RemotePolicy remotePolicy = RemotePolicy.HYBRID_OK;

    @Column(name = "deal_breakers", columnDefinition = "text") private String dealBreakers;

    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "preferences", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CandidateLanguage> languages = new ArrayList<>();

    public void addLanguage(CandidateLanguage language) {
        language.setPreferences(this);
        languages.add(language);
    }

    /** Splits on commas and drops blanks, so an unset field yields an empty list. */
    public List<String> acceptableMunicipalityList() {
        if (acceptableMunicipalities == null || acceptableMunicipalities.isBlank()) {
            return List.of();
        }
        return Arrays.stream(acceptableMunicipalities.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    public Long getId() { return id; }
    public String getHomeMunicipality() { return homeMunicipality; }
    public void setHomeMunicipality(String v) { this.homeMunicipality = v; }
    public String getAcceptableMunicipalities() { return acceptableMunicipalities; }
    public void setAcceptableMunicipalities(String v) { this.acceptableMunicipalities = v; }
    public RemotePolicy getRemotePolicy() { return remotePolicy; }
    public void setRemotePolicy(RemotePolicy remotePolicy) { this.remotePolicy = remotePolicy; }
    public String getDealBreakers() { return dealBreakers; }
    public void setDealBreakers(String dealBreakers) { this.dealBreakers = dealBreakers; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<CandidateLanguage> getLanguages() { return languages; }
}
```

- [ ] **Step 5: Write the repository**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/repo/JobPreferencesRepository.java`:

```java
package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobPreferences;

public interface JobPreferencesRepository extends JpaRepository<JobPreferences, Long> {

    /**
     * There is exactly one row, seeded by V7. Failing loudly beats silently creating a
     * second one: two preference rows would make the gates depend on which was read.
     */
    default JobPreferences findSingleton() {
        return findById(1L).orElseThrow(() ->
                new IllegalStateException("job_preferences row 1 is missing; V7 seeds it."));
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PreferencesPersistenceTest
```

Expected: PASS, all five tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/fit backend/src/test/java/se/caiowain/jobseeker/fit
git commit -m "feat: add job preferences with an ordered language level scale"
```

---

## Task 4: The language requirement detector

The Swedish IT market states language requirements in a small, recognisable vocabulary. This
component reads them and reports **the phrase it matched**, because a veto that cannot show
its evidence is not reviewable.

**Files:**
- Create: `fit/LanguageRequirement.java`, `fit/LanguageRequirementDetector.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/LanguageRequirementDetectorTest.java`

**Interfaces:**
- Consumes: `LanguageLevel` (Task 3).
- Produces: `record LanguageRequirement(String language, LanguageLevel level, String phrase)`;
  `LanguageRequirementDetector.detect(String adText) -> List<LanguageRequirement>`, at most
  one entry per language, empty when nothing matched.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/LanguageRequirementDetectorTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRequirementDetectorTest {

    private final LanguageRequirementDetector detector = new LanguageRequirementDetector();

    @Test
    void readsTheCommonSwedishFluencyBar() {
        var found = detector.detect("Vi söker en utvecklare med flytande svenska i tal och skrift.");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().language()).isEqualTo("sv");
        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.FLUENT);
    }

    @Test
    void reportsThePhraseItMatchedSoTheVetoCanBeReviewed() {
        var found = detector.detect("Du behöver svenska i tal och skrift för rollen.");

        assertThat(found.getFirst().phrase()).isEqualTo("svenska i tal och skrift");
        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.PROFESSIONAL);
    }

    @Test
    void readsBothLanguagesWhenAnAdRequiresBoth() {
        var found = detector.detect(
                "Vi kräver flytande svenska och goda kunskaper i engelska.");

        assertThat(found).hasSize(2);
        assertThat(found).extracting(LanguageRequirement::language)
                .containsExactlyInAnyOrder("sv", "en");
    }

    @Test
    void theMoreSpecificPhraseWinsOverTheOneItContains() {
        // "goda kunskaper i svenska" is a substring of "mycket goda kunskaper i svenska",
        // so pattern order decides the answer. The stronger bar must win.
        var found = detector.detect("Vi förutsätter mycket goda kunskaper i svenska.");

        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.FLUENT);
    }

    @Test
    void readsEnglishLanguageAds() {
        var found = detector.detect("We require fluent Swedish and professional English.");

        assertThat(found).extracting(LanguageRequirement::language)
                .containsExactlyInAnyOrder("sv", "en");
    }

    @Test
    void isNotFooledByCasingOrRaggedWhitespace() {
        var found = detector.detect("FLYTANDE   SVENSKA\n krävs.");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().level()).isEqualTo(LanguageLevel.FLUENT);
    }

    @Test
    void anAdStatingNoLanguageRequirementYieldsNothing() {
        // Nothing, not a pass. Turning this into PASS is what the UNKNOWN verdict exists
        // to prevent — see GateEvaluator.
        assertThat(detector.detect("We are hiring a backend engineer for our platform team."))
                .isEmpty();
    }

    @Test
    void nullAndBlankAdsAreSafe() {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("   ")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=LanguageRequirementDetectorTest
```

Expected: compilation failure — `LanguageRequirementDetector` does not exist.

- [ ] **Step 3: Write `LanguageRequirement`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/LanguageRequirement.java`:

```java
package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.fit.domain.LanguageLevel;

/**
 * A language demand read out of an ad.
 *
 * @param phrase the wording that produced this reading, kept so the gate can quote its
 *               own evidence back to the user instead of asserting a verdict
 */
public record LanguageRequirement(String language, LanguageLevel level, String phrase) {
}
```

- [ ] **Step 4: Write `LanguageRequirementDetector`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/LanguageRequirementDetector.java`:

```java
package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.fit.domain.LanguageLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads language demands out of a job ad. Pure: no Spring, no I/O.
 *
 * <p>Deliberately a phrase list rather than a model call. The vocabulary Swedish IT ads use
 * is small and stable, a list is auditable line by line, and it costs nothing to run across
 * the whole corpus. Its incompleteness is handled honestly rather than hidden: an ad this
 * list does not recognise produces no requirement, and the gate turns that into UNKNOWN.
 *
 * <p>Ordering is load-bearing. Patterns are tried in order and the first hit per language
 * wins, so a phrase that contains another must come first — otherwise "mycket goda kunskaper
 * i svenska" would be read as the weaker "goda kunskaper i svenska".
 */
public class LanguageRequirementDetector {

    private record Phrase(String text, String language, LanguageLevel level) {
    }

    private static final List<Phrase> PATTERNS = List.of(
            // Swedish, strongest first.
            new Phrase("svenska på modersmålsnivå", "sv", LanguageLevel.NATIVE),
            new Phrase("mycket goda kunskaper i svenska", "sv", LanguageLevel.FLUENT),
            new Phrase("flytande svenska", "sv", LanguageLevel.FLUENT),
            new Phrase("svenska flytande", "sv", LanguageLevel.FLUENT),
            new Phrase("obehindrat på svenska", "sv", LanguageLevel.FLUENT),
            new Phrase("fluent swedish", "sv", LanguageLevel.FLUENT),
            new Phrase("fluent in swedish", "sv", LanguageLevel.FLUENT),
            new Phrase("svenska i tal och skrift", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("goda kunskaper i svenska", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("behärskar svenska", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("professional swedish", "sv", LanguageLevel.PROFESSIONAL),
            new Phrase("grundläggande svenska", "sv", LanguageLevel.BASIC),

            // English, strongest first.
            new Phrase("mycket goda kunskaper i engelska", "en", LanguageLevel.FLUENT),
            new Phrase("flytande engelska", "en", LanguageLevel.FLUENT),
            new Phrase("obehindrat på engelska", "en", LanguageLevel.FLUENT),
            new Phrase("fluent english", "en", LanguageLevel.FLUENT),
            new Phrase("fluent in english", "en", LanguageLevel.FLUENT),
            new Phrase("engelska i tal och skrift", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("goda kunskaper i engelska", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("behärskar engelska", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("professional english", "en", LanguageLevel.PROFESSIONAL),
            new Phrase("grundläggande engelska", "en", LanguageLevel.BASIC));

    public List<LanguageRequirement> detect(String adText) {
        String haystack = normalise(adText);
        if (haystack.isEmpty()) {
            return List.of();
        }

        List<LanguageRequirement> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Phrase pattern : PATTERNS) {
            if (seen.contains(pattern.language())) {
                continue;
            }
            if (haystack.contains(pattern.text())) {
                found.add(new LanguageRequirement(
                        pattern.language(), pattern.level(), pattern.text()));
                seen.add(pattern.language());
            }
        }
        return found;
    }

    /**
     * Lowercase and collapse whitespace — and nothing else.
     *
     * <p>Diacritics are deliberately kept, unlike {@code SelectionGuard}'s fold. That guard
     * compares model output against an ad, where either side may have dropped an accent.
     * Here both sides are known: the patterns above are written with correct Swedish
     * spelling, and stripping å/ä/ö would only widen the match for no benefit.
     */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("\\s+", " ").strip();
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=LanguageRequirementDetectorTest
```

Expected: PASS, all eight tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/fit backend/src/test/java/se/caiowain/jobseeker/fit
git commit -m "feat: read language requirements out of ads, quoting the phrase"
```

---

## Task 5: The gate evaluator

**Files:**
- Create: `fit/GateOutcome.java`, `fit/GateEvaluator.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/GateEvaluatorTest.java`

**Interfaces:**
- Consumes: `LanguageRequirementDetector.detect` (Task 4); `JobPreferences`, `LanguageLevel`, `RemotePolicy`, `GateVerdict` (Task 3); `JobPosting` (Slice 1).
- Produces: `record GateOutcome(GateVerdict languageGate, String languageNote, GateVerdict locationGate, boolean deadlinePassed)`;
  `GateEvaluator.evaluate(JobPosting job, JobPreferences prefs, Instant now) -> GateOutcome`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/GateEvaluatorTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GateEvaluatorTest {

    private final GateEvaluator evaluator = new GateEvaluator(new LanguageRequirementDetector());
    private final Instant now = Instant.parse("2026-08-30T12:00:00Z");

    private JobPosting job(String description, String municipality) {
        JobPosting job = new JobPosting();
        job.setTitle("Backend Developer");
        job.setDescription(description);
        job.setMunicipality(municipality);
        return job;
    }

    private JobPreferences prefsSpeaking(LanguageLevel swedish) {
        JobPreferences prefs = new JobPreferences();
        prefs.setAcceptableMunicipalities("Stockholm, Solna");
        CandidateLanguage sv = new CandidateLanguage();
        sv.setLanguage("sv");
        sv.setLevel(swedish);
        prefs.addLanguage(sv);
        return prefs;
    }

    @Test
    void aLanguageYouDoNotSpeakAtAllIsAHardVeto() {
        JobPreferences prefs = new JobPreferences();
        prefs.setAcceptableMunicipalities("Stockholm");

        GateOutcome outcome = evaluator.evaluate(
                job("Vi kräver flytande svenska.", "Stockholm"), prefs, now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.FAIL);
        assertThat(outcome.languageNote()).contains("flytande svenska");
    }

    @Test
    void aBarAboveYourLevelFlagsRatherThanFailsBecauseFluentVariesByEmployer() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi kräver flytande svenska.", "Stockholm"),
                prefsSpeaking(LanguageLevel.CONVERSATIONAL), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.FLAG);
        assertThat(outcome.languageNote()).contains("flytande svenska");
    }

    @Test
    void aBarAtOrBelowYourLevelPasses() {
        GateOutcome outcome = evaluator.evaluate(
                job("Du behöver svenska i tal och skrift.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void anAdStatingNoLanguageRequirementIsUnknownNotPass() {
        GateOutcome outcome = evaluator.evaluate(
                job("We are hiring a backend engineer.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.UNKNOWN);
        assertThat(outcome.languageNote()).isNull();
    }

    @Test
    void theWorstVerdictAcrossLanguagesWinsAndItsPhraseIsTheOneQuoted() {
        // Swedish passes, English is not spoken at all. The veto must survive the pass.
        GateOutcome outcome = evaluator.evaluate(
                job("Svenska i tal och skrift samt flytande engelska.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.languageGate()).isEqualTo(GateVerdict.FAIL);
        assertThat(outcome.languageNote()).contains("flytande engelska");
    }

    @Test
    void aMunicipalityOutsideYourListFails() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi söker en utvecklare.", "Malmö"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.FAIL);
    }

    @Test
    void municipalityMatchingIgnoresCase() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi söker en utvecklare.", "STOCKHOLM"),
                prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void remoteOnlyMakesLocationIrrelevant() {
        JobPreferences prefs = prefsSpeaking(LanguageLevel.NATIVE);
        prefs.setRemotePolicy(RemotePolicy.REMOTE_ONLY);

        GateOutcome outcome = evaluator.evaluate(job("Vi söker.", "Kiruna"), prefs, now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void anEmptyMunicipalityListMeansNothingWasConfiguredToCheckAgainst() {
        JobPreferences prefs = prefsSpeaking(LanguageLevel.NATIVE);
        prefs.setAcceptableMunicipalities("");

        GateOutcome outcome = evaluator.evaluate(job("Vi söker.", "Kiruna"), prefs, now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void anAdWithNoMunicipalityIsUnknownNotPass() {
        GateOutcome outcome = evaluator.evaluate(
                job("Vi söker.", null), prefsSpeaking(LanguageLevel.NATIVE), now);

        assertThat(outcome.locationGate()).isEqualTo(GateVerdict.UNKNOWN);
    }

    @Test
    void aPastDeadlineIsFlaggedAndAFutureOneIsNot() {
        JobPosting expired = job("Vi söker.", "Stockholm");
        expired.setDeadlineAt(now.minus(1, ChronoUnit.DAYS));
        JobPosting open = job("Vi söker.", "Stockholm");
        open.setDeadlineAt(now.plus(1, ChronoUnit.DAYS));

        JobPreferences prefs = prefsSpeaking(LanguageLevel.NATIVE);

        assertThat(evaluator.evaluate(expired, prefs, now).deadlinePassed()).isTrue();
        assertThat(evaluator.evaluate(open, prefs, now).deadlinePassed()).isFalse();
    }

    @Test
    void anAdWithNoDeadlineHasNotExpired() {
        assertThat(evaluator.evaluate(job("Vi söker.", "Stockholm"),
                prefsSpeaking(LanguageLevel.NATIVE), now).deadlinePassed()).isFalse();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=GateEvaluatorTest
```

Expected: compilation failure — `GateEvaluator` does not exist.

- [ ] **Step 3: Write `GateOutcome`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/GateOutcome.java`:

```java
package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.fit.domain.GateVerdict;

/**
 * @param languageNote the phrase from the ad behind {@code languageGate}, or null when
 *                     nothing was recognised. A gate always shows its own evidence.
 */
public record GateOutcome(GateVerdict languageGate, String languageNote,
                          GateVerdict locationGate, boolean deadlinePassed) {
}
```

- [ ] **Step 4: Write `GateEvaluator`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/GateEvaluator.java`:

```java
package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns an ad plus your stated preferences into vetoes. Pure: no Spring, no I/O.
 *
 * <p>A gate never removes a posting. It produces a verdict and the phrase behind it, and the
 * job list sorts failures out of the default view — you may know something about your own
 * situation that these preferences do not record, so the verdict has to stay overridable.
 */
public class GateEvaluator {

    private final LanguageRequirementDetector detector;

    public GateEvaluator(LanguageRequirementDetector detector) {
        this.detector = detector;
    }

    public GateOutcome evaluate(JobPosting job, JobPreferences prefs, Instant now) {
        List<LanguageRequirement> required = detector.detect(job.getDescription());

        GateVerdict languageGate = GateVerdict.UNKNOWN;
        String languageNote = null;

        // The worst verdict wins: one language passing cannot cancel another one failing.
        for (LanguageRequirement requirement : required) {
            GateVerdict verdict = judge(requirement, prefs);
            if (severity(verdict) > severity(languageGate)) {
                languageGate = verdict;
                languageNote = requirement.phrase();
            }
        }

        return new GateOutcome(languageGate, languageNote,
                locationGate(job, prefs), deadlinePassed(job, now));
    }

    private GateVerdict judge(LanguageRequirement requirement, JobPreferences prefs) {
        Optional<CandidateLanguage> spoken = prefs.getLanguages().stream()
                .filter(l -> l.getLanguage() != null
                        && l.getLanguage().equalsIgnoreCase(requirement.language()))
                .findFirst();

        if (spoken.isEmpty()) {
            return GateVerdict.FAIL;
        }
        LanguageLevel level = spoken.get().getLevel();
        return level.atLeast(requirement.level()) ? GateVerdict.PASS : GateVerdict.FLAG;
    }

    private GateVerdict locationGate(JobPosting job, JobPreferences prefs) {
        if (prefs.getRemotePolicy() == RemotePolicy.REMOTE_ONLY) {
            return GateVerdict.PASS;
        }
        List<String> acceptable = prefs.acceptableMunicipalityList();
        if (acceptable.isEmpty()) {
            return GateVerdict.PASS;
        }
        String municipality = job.getMunicipality();
        if (municipality == null || municipality.isBlank()) {
            return GateVerdict.UNKNOWN;
        }
        return acceptable.stream().anyMatch(municipality::equalsIgnoreCase)
                ? GateVerdict.PASS
                : GateVerdict.FAIL;
    }

    private boolean deadlinePassed(JobPosting job, Instant now) {
        return job.getDeadlineAt() != null && job.getDeadlineAt().isBefore(now);
    }

    /** Severity order: FAIL beats FLAG beats PASS beats UNKNOWN. */
    private static int severity(GateVerdict verdict) {
        return switch (verdict) {
            case UNKNOWN -> 0;
            case PASS -> 1;
            case FLAG -> 2;
            case FAIL -> 3;
        };
    }
}
```

- [ ] **Step 5: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=GateEvaluatorTest
```

Expected: PASS, all twelve tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/se/caiowain/jobseeker/fit backend/src/test/java/se/caiowain/jobseeker/fit
git commit -m "feat: add deterministic language, location and deadline gates"
```

---

## Task 6: The prescreen

The corpus-wide stage. One SQL query finds which of your skills each ad names; the gates from
Task 5 run over every posting; the whole table is rebuilt. No model is involved, so this
works with Ollama stopped.

**Files:**
- Create: `fit/domain/JobPrescreen.java`, `fit/repo/JobPrescreenRepository.java`, `fit/SkillMatchQuery.java`, `fit/PrescreenSummary.java`, `fit/PrescreenService.java`, `fit/FitConfig.java`
- Create (test fixture): `src/test/java/se/caiowain/jobseeker/fit/FitFixtures.java`
- Modify: `domain/JobPosting.java`, `api/IngestController.java`, `ingest/IngestScheduler.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/PrescreenServiceTest.java`

**Interfaces:**
- Consumes: `GateEvaluator.evaluate` (Task 5), `JobPreferencesRepository.findSingleton` (Task 3), `CvProfileRepository.findFirstByOrderByIdDesc` (Slice 2a), `JobPostingRepository` (Slice 1), `ProfileNotReadyException` (moved to `profile` in Task 1).
- Produces: `record PrescreenSummary(int postings, int withMatches, Instant computedAt)`;
  `PrescreenService.run() -> PrescreenSummary`;
  `SkillMatchQuery.matchesByJob(Long profileId) -> Map<Long, List<String>>`;
  `JobPosting.getPrescreen() -> JobPrescreen`;
  `FitFixtures` static builders used by Tasks 7–10.

- [ ] **Step 1: Write the shared test fixture**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/FitFixtures.java`. Tasks 7 to 10 build the
same objects, so they live in one place rather than being copied per test.

```java
package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.CvSkill;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Minimal valid rows for the fit tests. Every NOT NULL column gets a value. */
public final class FitFixtures {

    private FitFixtures() {
    }

    public static CvDocument document(String sha) {
        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(4);
        doc.setSha256(sha);
        doc.setContent("%PDF".getBytes(StandardCharsets.UTF_8));
        doc.setUploadedAt(Instant.parse("2026-08-30T09:00:00Z"));
        return doc;
    }

    /** A READY profile carrying the given skill names, in the order supplied. */
    public static CvProfile readyProfile(CvDocument document, String... skillNames) {
        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setFullName("Test Candidate");
        profile.setStatus(ProfileStatus.READY);
        profile.setExtractedAt(Instant.parse("2026-08-30T09:05:00Z"));

        int ordinal = 0;
        for (String name : skillNames) {
            CvSkill skill = new CvSkill();
            skill.setName(name);
            skill.setOrdinal(ordinal++);
            profile.addSkill(skill);
        }
        return profile;
    }

    /** Adds one experience carrying the given bullet texts. */
    public static void withBullets(CvProfile profile, String... bulletTexts) {
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
    }

    public static JobPosting posting(String slug, String title, String description,
                                     String municipality) {
        JobPosting job = new JobPosting();
        job.setFingerprint(slug);
        job.setCanonicalUrl("https://example.test/jobs/" + slug);
        job.setTitle(title);
        job.setEmployerName("Example AB");
        job.setDescription(description);
        job.setMunicipality(municipality);
        job.setLanguage("sv");
        job.setFirstSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setLastSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setPublishedAt(Instant.parse("2026-08-29T08:00:00Z"));
        return job;
    }
}
```

- [ ] **Step 2: Write the failing prescreen test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/PrescreenServiceTest.java`. The first two
tests are the highest-value ones in the slice: they prove the assumption the whole approach
rests on, against a real PostgreSQL.

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PrescreenServiceTest extends AbstractIntegrationTest {

    @Autowired PrescreenService prescreen;
    @Autowired JobPostingRepository jobs;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    @BeforeEach
    void reset() {
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        CandidateLanguage swedish = new CandidateLanguage();
        swedish.setLanguage("sv");
        swedish.setLevel(LanguageLevel.NATIVE);
        prefs.addLanguage(swedish);
        prefs.setAcceptableMunicipalities("Stockholm");
        preferences.saveAndFlush(prefs);

        CvProfile profile = FitFixtures.readyProfile(
                documents.save(FitFixtures.document("a".repeat(64))),
                "Java", "Kubernetes", "Spring Boot", "COBOL");
        profiles.saveAndFlush(profile);
    }

    private Optional<JobPrescreen> prescreenFor(String fingerprint) {
        return jobs.findByFingerprint(fingerprint).stream()
                .findFirst()
                .flatMap(job -> prescreens.findByJobPostingId(job.getId()));
    }

    @Test
    void englishSkillTermsMatchInsideASwedishAd() {
        // The assumption the whole lexical approach rests on: Swedish IT ads name their
        // tools in English, so a skill row matches verbatim whatever language the ad is in.
        jobs.save(FitFixtures.posting("sv-ad", "Backend-utvecklare",
                "Vi söker en utvecklare som arbetat med Java och Kubernetes i produktion.",
                "Stockholm"));

        prescreen.run();

        JobPrescreen row = prescreenFor("sv-ad").orElseThrow();
        assertThat(row.getMatchedSkillCount()).isEqualTo(2);
        assertThat(row.getMatchedSkills()).contains("Java").contains("Kubernetes");
        assertThat(row.getMatchedSkills()).doesNotContain("COBOL");
    }

    @Test
    void aMultiWordSkillNeedsItsWordsAdjacent() {
        // phraseto_tsquery, not plainto_tsquery: an ad mentioning Spring in one sentence
        // and boot in another must not count as Spring Boot.
        jobs.save(FitFixtures.posting("loose", "Utvecklare",
                "Vi arbetar med Spring i backend. Vi har även ett boot camp för nyanställda.",
                "Stockholm"));

        prescreen.run();

        assertThat(prescreenFor("loose").orElseThrow().getMatchedSkills())
                .doesNotContain("Spring Boot");
    }

    @Test
    void anAdjacentMultiWordSkillDoesMatch() {
        jobs.save(FitFixtures.posting("adjacent", "Utvecklare",
                "Vi bygger tjänster i Spring Boot och deployar dem dagligen.", "Stockholm"));

        assertThat(prescreenFor("adjacent")).isEmpty();
        prescreen.run();

        assertThat(prescreenFor("adjacent").orElseThrow().getMatchedSkills())
                .contains("Spring Boot");
    }

    @Test
    void everyPostingGetsARowEvenWhenNothingMatches() {
        // A zero-match posting still needs its gates evaluated and still has to appear
        // in the list, so the rebuild covers the corpus rather than only the hits.
        jobs.save(FitFixtures.posting("nomatch", "Redovisningsekonom",
                "Vi söker en ekonom till vårt kontor.", "Stockholm"));

        prescreen.run();

        JobPrescreen row = prescreenFor("nomatch").orElseThrow();
        assertThat(row.getMatchedSkillCount()).isZero();
        assertThat(row.getLocationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void gatesAreEvaluatedAndTheirEvidenceStored() {
        jobs.save(FitFixtures.posting("gated", "Utvecklare",
                "Vi kräver flytande engelska. Java är ett plus.", "Malmö"));

        prescreen.run();

        JobPrescreen row = prescreenFor("gated").orElseThrow();
        assertThat(row.getLanguageGate()).isEqualTo(GateVerdict.FAIL);
        assertThat(row.getLanguageNote()).isEqualTo("flytande engelska");
        assertThat(row.getLocationGate()).isEqualTo(GateVerdict.FAIL);
    }

    @Test
    void aRerunReplacesRowsRatherThanAccumulatingThem() {
        jobs.save(FitFixtures.posting("once", "Utvecklare", "Java och Kubernetes.", "Stockholm"));

        prescreen.run();
        prescreen.run();
        prescreen.run();

        assertThat(prescreens.count()).isEqualTo(1);
    }

    @Test
    void theSummaryReportsWhatItCovered() {
        jobs.save(FitFixtures.posting("hit", "Utvecklare", "Vi kör Java.", "Stockholm"));
        jobs.save(FitFixtures.posting("miss", "Ekonom", "Vi söker en ekonom.", "Stockholm"));

        PrescreenSummary summary = prescreen.run();

        assertThat(summary.postings()).isEqualTo(2);
        assertThat(summary.withMatches()).isEqualTo(1);
    }

    @Test
    void aProfileThatHasNotBeenApprovedStopsTheRun() {
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(se.caiowain.jobseeker.profile.domain.ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> prescreen.run())
                .isInstanceOf(se.caiowain.jobseeker.profile.ProfileNotReadyException.class);
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PrescreenServiceTest
```

Expected: compilation failure — `PrescreenService` does not exist.

- [ ] **Step 4: Write the `JobPrescreen` entity**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/JobPrescreen.java`:

```java
package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.time.Instant;

/**
 * The cheap, corpus-wide ranking signal for one posting.
 *
 * <p><b>Disposable.</b> Every prescreen run truncates this table and rebuilds it. Nothing
 * that must survive — a triage decision, a deep score — may hold a foreign key into it.
 *
 * <p>{@code matchedSkillCount} is a count of facts, not a score: it is how many of your own
 * skill rows this ad names. There is deliberately no synthetic 0–100 here.
 */
@Entity
@Table(name = "job_prescreen")
public class JobPrescreen {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false, unique = true)
    private JobPosting jobPosting;

    /** Which profile produced this row. A stamp, not part of its identity. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(name = "matched_skill_count", nullable = false)
    private int matchedSkillCount;

    /** Comma-separated names, for display. The count is what orders the list. */
    @Column(name = "matched_skills", columnDefinition = "text")
    private String matchedSkills;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_gate", nullable = false, length = 16)
    private GateVerdict languageGate = GateVerdict.UNKNOWN;

    /** The phrase from the ad behind {@code languageGate}. A veto shows its evidence. */
    @Column(name = "language_note", columnDefinition = "text")
    private String languageNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_gate", nullable = false, length = 16)
    private GateVerdict locationGate = GateVerdict.UNKNOWN;

    @Column(name = "deadline_passed", nullable = false)
    private boolean deadlinePassed;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public int getMatchedSkillCount() { return matchedSkillCount; }
    public void setMatchedSkillCount(int v) { this.matchedSkillCount = v; }
    public String getMatchedSkills() { return matchedSkills; }
    public void setMatchedSkills(String matchedSkills) { this.matchedSkills = matchedSkills; }
    public GateVerdict getLanguageGate() { return languageGate; }
    public void setLanguageGate(GateVerdict languageGate) { this.languageGate = languageGate; }
    public String getLanguageNote() { return languageNote; }
    public void setLanguageNote(String languageNote) { this.languageNote = languageNote; }
    public GateVerdict getLocationGate() { return locationGate; }
    public void setLocationGate(GateVerdict locationGate) { this.locationGate = locationGate; }
    public boolean isDeadlinePassed() { return deadlinePassed; }
    public void setDeadlinePassed(boolean deadlinePassed) { this.deadlinePassed = deadlinePassed; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
```

- [ ] **Step 5: Add the inverse mapping to `JobPosting`**

In `backend/src/main/java/se/caiowain/jobseeker/domain/JobPosting.java`, add the import and the
association. This adds **no column** — it is the inverse side, so `job_posting` is unchanged
apart from the generated `search_tsv` from Task 2. It exists so the job list can left-join fit
data in one query instead of issuing a second one per page.

Add after the existing `sources` field:

```java
    /**
     * Inverse side; adds no column. Present so the job list can sort and filter by fit in
     * a single left join. Guaranteed at most one row by the unique index on
     * {@code job_prescreen (job_posting_id)}.
     */
    @OneToOne(mappedBy = "jobPosting", fetch = FetchType.LAZY)
    private JobPrescreen prescreen;
```

Add the import:

```java
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
```

Add the getter beside the others:

```java
    public JobPrescreen getPrescreen() { return prescreen; }
```

- [ ] **Step 6: Write the repository**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/repo/JobPrescreenRepository.java`:

```java
package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;

import java.util.Optional;

public interface JobPrescreenRepository extends JpaRepository<JobPrescreen, Long> {
    Optional<JobPrescreen> findByJobPostingId(Long jobPostingId);
}
```

- [ ] **Step 7: Write `SkillMatchQuery`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/SkillMatchQuery.java`:

```java
package se.caiowain.jobseeker.fit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one full-text query behind the prescreen: which of the candidate's skills does each
 * ad name?
 *
 * <p>Written with {@link JdbcTemplate} rather than a JPA projection so the SQL is readable
 * as SQL — this is the component whose exact wording decides whether the ranking is right.
 *
 * <p>Two choices are load-bearing and must not be "simplified":
 * <ul>
 *   <li>{@code 'simple'} — a generated column needs an IMMUTABLE expression, and stemming
 *       would mangle proper nouns like Spring and React.</li>
 *   <li>{@code phraseto_tsquery} — {@code plainto_tsquery} ANDs the lexemes anywhere in the
 *       document, so "Spring Boot" would match an ad mentioning the two words paragraphs
 *       apart. A phrase query requires adjacency.</li>
 * </ul>
 *
 * <p>A blank skill name yields an empty tsquery, which matches nothing. That is the desired
 * behaviour, so no special case is needed.
 */
@Component
public class SkillMatchQuery {

    private static final String SQL = """
            SELECT p.id AS job_id, s.name AS skill_name
            FROM job_posting p
            JOIN cv_skill s ON s.cv_profile_id = ?
            WHERE p.search_tsv @@ phraseto_tsquery('simple', s.name)
            ORDER BY p.id, s.ordinal
            """;

    private final JdbcTemplate jdbc;

    public SkillMatchQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Job posting id → the skill names that posting mentions, in profile order. */
    public Map<Long, List<String>> matchesByJob(Long profileId) {
        Map<Long, List<String>> byJob = new LinkedHashMap<>();
        jdbc.query(SQL, rs -> {
            byJob.computeIfAbsent(rs.getLong("job_id"), key -> new ArrayList<>())
                    .add(rs.getString("skill_name"));
        }, profileId);
        return byJob;
    }
}
```

- [ ] **Step 8: Write `PrescreenSummary` and `PrescreenService`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/PrescreenSummary.java`:

```java
package se.caiowain.jobseeker.fit;

import java.time.Instant;

public record PrescreenSummary(int postings, int withMatches, Instant computedAt) {
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/PrescreenService.java`:

```java
package se.caiowain.jobseeker.fit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the whole prescreen table.
 *
 * <p>Truncate-and-rebuild rather than upsert, because the inputs are global: changing one
 * preference or approving a new CV invalidates every row at once, and a partial update would
 * leave the list a mix of two answers.
 *
 * <p>It writes only to {@code job_prescreen}. Triage decisions and deep scores live in their
 * own tables with no foreign key into this one, so a rebuild cannot reach them.
 */
@Service
public class PrescreenService {

    private final JobPostingRepository jobs;
    private final JobPrescreenRepository prescreens;
    private final CvProfileRepository profiles;
    private final JobPreferencesRepository preferences;
    private final SkillMatchQuery skillMatches;
    private final GateEvaluator gates;

    public PrescreenService(JobPostingRepository jobs,
                            JobPrescreenRepository prescreens,
                            CvProfileRepository profiles,
                            JobPreferencesRepository preferences,
                            SkillMatchQuery skillMatches,
                            GateEvaluator gates) {
        this.jobs = jobs;
        this.prescreens = prescreens;
        this.profiles = profiles;
        this.preferences = preferences;
        this.skillMatches = skillMatches;
        this.gates = gates;
    }

    @Transactional
    public PrescreenSummary run() {
        CvProfile profile = profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .orElseThrow(() -> new ProfileNotReadyException(
                        "Approve your CV profile before ranking jobs."));

        JobPreferences prefs = preferences.findSingleton();
        Map<Long, List<String>> matches = skillMatches.matchesByJob(profile.getId());
        Instant now = Instant.now();

        prescreens.deleteAllInBatch();
        prescreens.flush();

        List<JobPrescreen> rows = new ArrayList<>();
        int withMatches = 0;

        for (JobPosting job : jobs.findAll()) {
            List<String> skills = matches.getOrDefault(job.getId(), List.of());
            if (!skills.isEmpty()) {
                withMatches++;
            }
            GateOutcome outcome = gates.evaluate(job, prefs, now);

            JobPrescreen row = new JobPrescreen();
            row.setJobPosting(job);
            row.setCvProfile(profile);
            row.setMatchedSkillCount(skills.size());
            row.setMatchedSkills(skills.isEmpty() ? null : String.join(", ", skills));
            row.setLanguageGate(outcome.languageGate());
            row.setLanguageNote(outcome.languageNote());
            row.setLocationGate(outcome.locationGate());
            row.setDeadlinePassed(outcome.deadlinePassed());
            row.setComputedAt(now);
            rows.add(row);
        }

        prescreens.saveAll(rows);
        return new PrescreenSummary(rows.size(), withMatches, now);
    }
}
```

- [ ] **Step 9: Write `FitConfig`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/FitConfig.java`:

```java
package se.caiowain.jobseeker.fit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kept free of Spring annotations so their tests stay pure — same pattern as TailoringConfig. */
@Configuration
public class FitConfig {

    @Bean public LanguageRequirementDetector languageRequirementDetector() {
        return new LanguageRequirementDetector();
    }

    @Bean public GateEvaluator gateEvaluator(LanguageRequirementDetector detector) {
        return new GateEvaluator(detector);
    }
}
```

- [ ] **Step 10: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PrescreenServiceTest
```

Expected: PASS, all eight tests. If `englishSkillTermsMatchInsideASwedishAd` fails, the
`'simple'` config or the generated column is wrong — do not work around it by loosening the
match, because that test is the approach's justification.

- [ ] **Step 10b: Re-rank automatically after an ingest run**

The spec has the prescreen running after each ingest, not only on demand — newly discovered
postings are exactly the ones you want ranked. It has to be best-effort: ingest runs perfectly
well before any CV has been approved, and a missing profile must not turn a successful ingest
into a failure.

Add the quiet wrapper to `backend/src/main/java/se/caiowain/jobseeker/fit/PrescreenService.java`:

```java
    /**
     * For callers that want the ranking refreshed but must not fail without it — ingest runs
     * long before a CV is ever approved. Returns empty when there is nothing to rank against.
     */
    public Optional<PrescreenSummary> runQuietly() {
        try {
            return Optional.of(run());
        } catch (ProfileNotReadyException e) {
            log.info("Skipping the ranking after ingest: {}", e.getMessage());
            return Optional.empty();
        }
    }
```

with the extra imports and the logger:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
```

```java
    private static final Logger log = LoggerFactory.getLogger(PrescreenService.class);
```

Add the test to `PrescreenServiceTest`:

```java
    @Test
    void theQuietRunSkipsRatherThanFailsWhenNoProfileIsApproved() {
        // Ingest happens long before a CV is approved. A missing profile must not turn a
        // successful ingest run into a failed one.
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(se.caiowain.jobseeker.profile.domain.ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        assertThat(prescreen.runQuietly()).isEmpty();
    }
```

Wire the two callers. In `backend/src/main/java/se/caiowain/jobseeker/api/IngestController.java`,
take `PrescreenService` in the constructor and call it after the run:

```java
    @PostMapping("/run")
    public List<IngestRunDto> run() {
        List<IngestRunDto> results =
                orchestrator.runAll().stream().map(IngestController::toDto).toList();
        prescreen.runQuietly();
        return results;
    }
```

In `backend/src/main/java/se/caiowain/jobseeker/ingest/IngestScheduler.java`, take
`PrescreenService` in the constructor and add one line at the end of `scheduledIngest()`:

```java
        prescreen.runQuietly().ifPresent(summary ->
                log.info("  ranked {} postings", summary.postings()));
```

`IngestOrchestrator` is deliberately left alone. Its constructor is exercised directly by
`IngestOrchestratorTest`, and per-source isolation is its whole contract — re-ranking is the
caller's concern, not the orchestrator's.

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=PrescreenServiceTest
```

Expected: PASS, all nine tests.

- [ ] **Step 11: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
cd ..
git add backend/src
git commit -m "feat: add the full-text prescreen over the whole corpus"
```

---

## Task 7: Triage

**Files:**
- Create: `fit/domain/TriageState.java`, `fit/domain/JobTriage.java`, `fit/repo/JobTriageRepository.java`, `fit/TriageService.java`
- Modify: `domain/JobPosting.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/TriageServiceTest.java`

**Interfaces:**
- Consumes: `FitFixtures` (Task 6), `PrescreenService.run` (Task 6), `JobPostingRepository`.
- Produces: `TriageService.decide(Long jobId, TriageState state, String note) -> JobTriage`;
  `JobPosting.getTriage() -> JobTriage`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/TriageServiceTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TriageServiceTest extends AbstractIntegrationTest {

    @Autowired TriageService triage;
    @Autowired PrescreenService prescreen;
    @Autowired JobTriageRepository triages;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    private Long jobId;

    @BeforeEach
    void seed() {
        triages.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        CandidateLanguage sv = new CandidateLanguage();
        sv.setLanguage("sv");
        sv.setLevel(LanguageLevel.NATIVE);
        prefs.addLanguage(sv);
        prefs.setAcceptableMunicipalities("Stockholm");
        preferences.saveAndFlush(prefs);

        profiles.saveAndFlush(FitFixtures.readyProfile(
                documents.save(FitFixtures.document("b".repeat(64))), "Java"));

        JobPosting job = jobs.save(FitFixtures.posting(
                "triage-1", "Utvecklare", "Vi kör Java.", "Stockholm"));
        jobId = job.getId();
    }

    @Test
    void aDecisionIsRecordedWithItsNote() {
        JobTriage decision = triage.decide(jobId, TriageState.SHORTLISTED, "worth a look");

        assertThat(decision.getState()).isEqualTo(TriageState.SHORTLISTED);
        assertThat(decision.getNote()).isEqualTo("worth a look");
        assertThat(decision.getDecidedAt()).isNotNull();
    }

    @Test
    void decidingTwiceUpdatesTheSameRowRatherThanAddingOne() {
        triage.decide(jobId, TriageState.SHORTLISTED, null);
        triage.decide(jobId, TriageState.DISMISSED, "wrong stack after all");

        assertThat(triages.count()).isEqualTo(1);
        assertThat(triages.findByJobPostingId(jobId).orElseThrow().getState())
                .isEqualTo(TriageState.DISMISSED);
    }

    @Test
    void aPrescreenRebuildLeavesDecisionsAlone() {
        // The load-bearing guarantee of the whole slice. A one-second job must never be
        // able to destroy a judgement — or, in Task 8, a 94-second measurement.
        triage.decide(jobId, TriageState.DISMISSED, "not for me");

        prescreen.run();
        prescreen.run();

        assertThat(triages.findByJobPostingId(jobId).orElseThrow().getState())
                .isEqualTo(TriageState.DISMISSED);
    }

    @Test
    void anUnknownJobIsRejected() {
        assertThatThrownBy(() -> triage.decide(999_999L, TriageState.SHORTLISTED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=TriageServiceTest
```

Expected: compilation failure — `TriageService` does not exist.

- [ ] **Step 3: Write `TriageState` and `JobTriage`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/TriageState.java`:

```java
package se.caiowain.jobseeker.fit.domain;

/**
 * What you decided about a posting.
 *
 * <p>{@code SHORTLISTED} means only "worth my attention" — it asserts nothing about an
 * application. Tailoring has its own separate status vocabulary (Slice 2b).
 */
public enum TriageState { NEW, SHORTLISTED, DISMISSED }
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/JobTriage.java`:

```java
package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;

import java.time.Instant;

/**
 * Your decision about a posting.
 *
 * <p><b>Preserved.</b> Nothing recomputes this. It survives prescreen rebuilds, CV
 * re-extraction and ingest runs, because it is a judgement rather than a derivation —
 * which is exactly why it does not live in {@code job_prescreen}.
 */
@Entity
@Table(name = "job_triage")
public class JobTriage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false, unique = true)
    private JobPosting jobPosting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TriageState state = TriageState.NEW;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(columnDefinition = "text")
    private String note;

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public TriageState getState() { return state; }
    public void setState(TriageState state) { this.state = state; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
```

- [ ] **Step 4: Add the inverse mapping to `JobPosting`**

In `backend/src/main/java/se/caiowain/jobseeker/domain/JobPosting.java`, beside the `prescreen`
field added in Task 6:

```java
    /** Inverse side; adds no column. Lets the job list filter by decision in one join. */
    @OneToOne(mappedBy = "jobPosting", fetch = FetchType.LAZY)
    private JobTriage triage;
```

with the import:

```java
import se.caiowain.jobseeker.fit.domain.JobTriage;
```

and the getter:

```java
    public JobTriage getTriage() { return triage; }
```

- [ ] **Step 5: Write the repository and the service**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/repo/JobTriageRepository.java`:

```java
package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobTriage;

import java.util.Optional;

public interface JobTriageRepository extends JpaRepository<JobTriage, Long> {
    Optional<JobTriage> findByJobPostingId(Long jobPostingId);
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/TriageService.java`:

```java
package se.caiowain.jobseeker.fit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.time.Instant;

@Service
public class TriageService {

    private final JobTriageRepository triages;
    private final JobPostingRepository jobs;

    public TriageService(JobTriageRepository triages, JobPostingRepository jobs) {
        this.triages = triages;
        this.jobs = jobs;
    }

    /** Records or replaces your decision about one posting. One row per posting. */
    @Transactional
    public JobTriage decide(Long jobId, TriageState state, String note) {
        JobPosting job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));

        JobTriage decision = triages.findByJobPostingId(jobId).orElseGet(() -> {
            JobTriage fresh = new JobTriage();
            fresh.setJobPosting(job);
            return fresh;
        });

        decision.setState(state);
        decision.setNote(note);
        decision.setDecidedAt(Instant.now());
        return triages.save(decision);
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=TriageServiceTest
```

Expected: PASS, all four tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: record shortlist and dismiss decisions that survive rebuilds"
```

---

## Task 8: Deep fit scoring

The on-demand stage. It reuses Slice 2b's prompt, client and guards **unchanged** — the only
difference is what is persisted: a coverage number and the requirements that matched nothing,
rather than a full application package. No new prompt, no new guard, no new model failure mode.

**Files:**
- Create: `fit/domain/JobDeepFit.java`, `fit/domain/JobDeepFitGap.java`, `fit/repo/JobDeepFitRepository.java`, `fit/DeepFitService.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/DeepFitServiceTest.java`

**Interfaces:**
- Consumes: `OllamaSelectionClient.select`, `SelectionGuard.check`, `SelectionPromptBuilder.numberBullets`, `Coverage.percent`, `SelectionRejectedException`, `ModelUnavailableException` (all `se.caiowain.jobseeker.select`, Task 1); `ProfileNotReadyException` (`se.caiowain.jobseeker.profile`, Task 1); `FitFixtures` (Task 6).
- Produces: `DeepFitService.score(Long jobId) -> JobDeepFit`; `JobDeepFit.getGaps() -> List<JobDeepFitGap>`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/DeepFitServiceTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;
import se.caiowain.jobseeker.fit.domain.JobDeepFitGap;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.select.ModelUnavailableException;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionRejectedException;
import se.caiowain.jobseeker.select.SelectionResult;
import se.caiowain.jobseeker.select.SelectionResult.RequirementSelection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
class DeepFitServiceTest extends AbstractIntegrationTest {

    @Autowired DeepFitService deepFit;
    @Autowired PrescreenService prescreen;
    @Autowired JobDeepFitRepository deepFits;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    /** The only model caller is replaced; no test reaches the network. */
    @MockitoBean OllamaSelectionClient selectionClient;

    /** Every requirement below is a verbatim substring of this, or Guard B rejects it. */
    private static final String DESCRIPTION = """
            Vi söker en utvecklare. Du har erfarenhet av Java och bygger REST-API:er.
            Vi ser gärna att du har arbetat med Kubernetes och att du kan AWS.
            """;

    private Long jobId;

    @BeforeEach
    void seed() {
        deepFits.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPosting job = FitFixtures.posting("deep-1", "Utvecklare", DESCRIPTION, "Stockholm");
        jobId = jobs.save(job).getId();

        CvProfile profile = FitFixtures.readyProfile(
                documents.save(FitFixtures.document("c".repeat(64))), "Java");
        FitFixtures.withBullets(profile,
                "Byggde REST-API:er i Java och Spring Boot",
                "Drev migrering av en monolit till moduler");
        profiles.saveAndFlush(profile);

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
    }

    private void modelReturns(SelectionResult result) {
        doReturn(result).when(selectionClient).select(anyString(), any());
    }

    @Test
    void coverageIsComputedInJavaFromCountedRows() {
        // Three requirements, two supported: 2/3 = 67. The model supplies no number here.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1)),
                new RequirementSelection("bygger REST-API:er", List.of(1, 2)),
                new RequirementSelection("arbetat med Kubernetes", List.of())),
                List.of(1, 2)));

        JobDeepFit fit = deepFit.score(jobId);

        assertThat(fit.getRequirementCount()).isEqualTo(3);
        assertThat(fit.getCoveragePercent()).isEqualTo(67);
    }

    @Test
    void theGapsAreTheRequirementsNothingSupports() {
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1)),
                new RequirementSelection("arbetat med Kubernetes", List.of()),
                new RequirementSelection("du kan AWS", List.of())),
                List.of(1)));

        JobDeepFit fit = deepFit.score(jobId);

        assertThat(fit.getGaps()).extracting(JobDeepFitGap::getText)
                .containsExactly("arbetat med Kubernetes", "du kan AWS");
    }

    @Test
    void fullCoverageLeavesNoGaps() {
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1)));

        JobDeepFit fit = deepFit.score(jobId);

        assertThat(fit.getCoveragePercent()).isEqualTo(100);
        assertThat(fit.getGaps()).isEmpty();
    }

    @Test
    void aPhraseAbsentFromTheAdIsRejectedAfterOneRetryAndNothingIsPersisted() {
        // Guard B. The Spike 1 failure was the model reattributing an ad's words to the
        // candidate; a phrase that is not in the ad is not a quotation.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("tio års erfarenhet av Rust", List.of(1))),
                List.of(1)));

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(SelectionRejectedException.class);

        assertThat(deepFits.count()).isZero();
    }

    @Test
    void aBulletNumberThatDoesNotExistIsRejected() {
        // Guard A. The profile has two bullets; 9 is not one of them.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(9))),
                List.of(9)));

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(SelectionRejectedException.class);

        assertThat(deepFits.count()).isZero();
    }

    @Test
    void rescoringReplacesTheEarlierResultRatherThanAddingOne() {
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1)),
                new RequirementSelection("du kan AWS", List.of())),
                List.of(1)));
        deepFit.score(jobId);

        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1)));
        JobDeepFit second = deepFit.score(jobId);

        assertThat(deepFits.count()).isEqualTo(1);
        assertThat(second.getCoveragePercent()).isEqualTo(100);
        assertThat(second.getGaps()).isEmpty();
    }

    @Test
    void aPrescreenRebuildLeavesADeepScoreAlone() {
        // 94 seconds of work must survive a one-second job.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1)));
        deepFit.score(jobId);

        prescreen.run();
        prescreen.run();

        assertThat(deepFits.count()).isEqualTo(1);
        assertThat(deepFits.findAll().getFirst().getCoveragePercent()).isEqualTo(100);
    }

    @Test
    void anUnreachableModelSurfacesAsUnavailableRatherThanAsAZeroScore() {
        doThrow(new ModelUnavailableException("Could not reach the local model"))
                .when(selectionClient).select(anyString(), any());

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(ModelUnavailableException.class);

        assertThat(deepFits.count()).isZero();
    }

    @Test
    void anUnapprovedProfileStopsTheRun() {
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(ProfileNotReadyException.class);
    }

    @Test
    void anUnknownJobIsRejected() {
        assertThatThrownBy(() -> deepFit.score(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=DeepFitServiceTest
```

Expected: compilation failure — `DeepFitService` does not exist.

- [ ] **Step 3: Write the entities**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/JobDeepFitGap.java`:

```java
package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;

/**
 * A requirement the ad states that nothing in the CV answers.
 *
 * <p>The text is a phrase quoted verbatim from the ad — Guard B enforces that — so it is
 * the employer's words, never a claim about the candidate. These rows are the most useful
 * output of a deep score: they name the honest distance between you and the job.
 */
@Entity
@Table(name = "job_deep_fit_gap")
public class JobDeepFitGap {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_deep_fit_id", nullable = false)
    private JobDeepFit deepFit;

    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public JobDeepFit getDeepFit() { return deepFit; }
    public void setDeepFit(JobDeepFit deepFit) { this.deepFit = deepFit; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/domain/JobDeepFit.java`:

```java
package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One measured job, from roughly 94 seconds of local-model time.
 *
 * <p><b>Preserved.</b> Keyed on (posting, profile) so a superseded profile's scores stay as
 * history rather than being silently overwritten by a CV re-upload.
 *
 * <p>{@code coveragePercent} is computed in Java by {@code Coverage}, from counted rows. It
 * is not a model's opinion and not a fit score in the judgemental sense — it is the fraction
 * of the ad's stated requirements that your own bullets answer.
 */
@Entity
@Table(name = "job_deep_fit")
public class JobDeepFit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(name = "coverage_percent", nullable = false) private int coveragePercent;
    @Column(name = "requirement_count", nullable = false) private int requirementCount;
    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "deep_scored_at", nullable = false) private Instant deepScoredAt;

    @OneToMany(mappedBy = "deepFit", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<JobDeepFitGap> gaps = new ArrayList<>();

    public void addGap(JobDeepFitGap gap) {
        gap.setDeepFit(this);
        gaps.add(gap);
    }

    public Long getId() { return id; }
    public JobPosting getJobPosting() { return jobPosting; }
    public void setJobPosting(JobPosting jobPosting) { this.jobPosting = jobPosting; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public int getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(int v) { this.coveragePercent = v; }
    public int getRequirementCount() { return requirementCount; }
    public void setRequirementCount(int v) { this.requirementCount = v; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public Instant getDeepScoredAt() { return deepScoredAt; }
    public void setDeepScoredAt(Instant deepScoredAt) { this.deepScoredAt = deepScoredAt; }
    public List<JobDeepFitGap> getGaps() { return gaps; }
}
```

- [ ] **Step 4: Write the repository**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/repo/JobDeepFitRepository.java`:

```java
package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;

import java.util.Optional;

public interface JobDeepFitRepository extends JpaRepository<JobDeepFit, Long> {
    Optional<JobDeepFit> findByJobPostingIdAndCvProfileId(Long jobPostingId, Long cvProfileId);
    Optional<JobDeepFit> findFirstByJobPostingIdOrderByDeepScoredAtDesc(Long jobPostingId);
}
```

- [ ] **Step 5: Write `DeepFitService`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/DeepFitService.java`:

```java
package se.caiowain.jobseeker.fit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;
import se.caiowain.jobseeker.fit.domain.JobDeepFitGap;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.select.Coverage;
import se.caiowain.jobseeker.select.NumberedBullet;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionGuard;
import se.caiowain.jobseeker.select.SelectionPromptBuilder;
import se.caiowain.jobseeker.select.SelectionRejectedException;
import se.caiowain.jobseeker.select.SelectionResult;

import java.time.Instant;
import java.util.List;

/**
 * Measures one job against the profile, on demand, in roughly 94 seconds.
 *
 * <p>Reuses the tailoring slice's prompt, client and guards without modification. The only
 * difference is what is kept: a coverage number and the unanswered requirements, rather than
 * an assembled application. Nothing here is persisted unless the guards accept the result —
 * a rejected run leaves no row, so a failure can never be mistaken for a low score.
 */
@Service
public class DeepFitService {

    private static final Logger log = LoggerFactory.getLogger(DeepFitService.class);

    private final JobDeepFitRepository deepFits;
    private final JobPostingRepository jobs;
    private final CvProfileRepository profiles;
    private final OllamaSelectionClient selectionClient;
    private final SelectionPromptBuilder prompts;
    private final SelectionGuard guard;
    private final int maxBulletsPerRequirement;

    public DeepFitService(JobDeepFitRepository deepFits,
                          JobPostingRepository jobs,
                          CvProfileRepository profiles,
                          OllamaSelectionClient selectionClient,
                          SelectionPromptBuilder prompts,
                          SelectionGuard guard,
                          @Value("${jobseeker.select.max-bullets-per-requirement:3}")
                          int maxBulletsPerRequirement) {
        this.deepFits = deepFits;
        this.jobs = jobs;
        this.profiles = profiles;
        this.selectionClient = selectionClient;
        this.prompts = prompts;
        this.guard = guard;
        this.maxBulletsPerRequirement = maxBulletsPerRequirement;
    }

    @Transactional
    public JobDeepFit score(Long jobId) {
        JobPosting job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));

        CvProfile profile = profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .orElseThrow(() -> new ProfileNotReadyException(
                        "Approve your CV profile before scoring a job."));

        List<NumberedBullet> bullets = prompts.numberBullets(profile);
        if (bullets.isEmpty()) {
            throw new ProfileNotReadyException(
                    "Your profile has no experience bullets to match against.");
        }

        // Replacing rather than refusing: unlike a tailored application there is no
        // approved prose here to protect, so a re-score is always safe.
        deepFits.findByJobPostingIdAndCvProfileId(jobId, profile.getId())
                .ifPresent(existing -> {
                    deepFits.delete(existing);
                    deepFits.flush();
                });

        SelectionGuard.GuardVerdict lastVerdict = null;

        // One retry, matching the tailoring slice: small models quote loosely on a first pass.
        for (int attempt = 1; attempt <= 2; attempt++) {
            SelectionResult result = selectionClient.select(job.getDescription(), bullets);
            SelectionGuard.GuardVerdict verdict = guard.check(
                    result, job.getDescription(), bullets.size(), maxBulletsPerRequirement);
            lastVerdict = verdict;

            if (verdict.accepted()) {
                return deepFits.save(build(result, job, profile));
            }
            log.warn("Deep fit attempt {} rejected for job {}: {}",
                    attempt, jobId, verdict.violations());
        }

        throw new SelectionRejectedException(
                "The model's selection failed validation twice.", lastVerdict.violations());
    }

    private JobDeepFit build(SelectionResult result, JobPosting job, CvProfile profile) {
        List<SelectionResult.RequirementSelection> requirements = result.requirements();

        JobDeepFit fit = new JobDeepFit();
        fit.setJobPosting(job);
        fit.setCvProfile(profile);
        fit.setRequirementCount(requirements.size());
        fit.setModelUsed(selectionClient.modelName());
        fit.setDeepScoredAt(Instant.now());

        int withEvidence = 0;
        int ordinal = 0;
        for (SelectionResult.RequirementSelection requirement : requirements) {
            boolean supported = requirement.bulletIds() != null
                    && !requirement.bulletIds().isEmpty();
            if (supported) {
                withEvidence++;
            } else {
                JobDeepFitGap gap = new JobDeepFitGap();
                gap.setText(requirement.text());
                gap.setOrdinal(ordinal++);
                fit.addGap(gap);
            }
        }

        fit.setCoveragePercent(Coverage.percent(withEvidence, requirements.size()));
        return fit;
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=DeepFitServiceTest
```

Expected: PASS, all ten tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: measure one job's requirement coverage with the 2b selection call"
```

---

## Task 9: The fit and preferences REST API

Note there is **no new exception handler**. `ApiExceptionHandler` (moved to the shared `api`
package in Task 1) is `@RestControllerAdvice`, so it already applies application-wide:
`ProfileNotReadyException` → 409, `ModelUnavailableException` → 503,
`SelectionRejectedException` → 422, `IllegalArgumentException` → 404. A second advice
handling the same types would be ambiguous, so do not add one.

**Files:**
- Create: `fit/api/dto/PreferencesDto.java`, `fit/api/dto/CandidateLanguageDto.java`, `fit/api/dto/TriageRequest.java`, `fit/api/dto/DeepFitDto.java`, `fit/api/dto/PrescreenSummaryDto.java`, `fit/api/PreferencesController.java`, `fit/api/FitController.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/api/FitApiTest.java`

**Interfaces:**
- Consumes: `PrescreenService.run`, `DeepFitService.score`, `TriageService.decide`, `JobPreferencesRepository`.
- Produces: `POST /api/fit/prescreen`, `POST /api/jobs/{id}/fit`, `GET /api/jobs/{id}/fit`, `PUT /api/jobs/{id}/triage`, `GET`/`PUT /api/preferences`.

- [ ] **Step 1: Write the DTOs**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/dto/CandidateLanguageDto.java`:

```java
package se.caiowain.jobseeker.fit.api.dto;

import se.caiowain.jobseeker.fit.domain.LanguageLevel;

public record CandidateLanguageDto(String language, LanguageLevel level) {
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/dto/PreferencesDto.java`:

```java
package se.caiowain.jobseeker.fit.api.dto;

import se.caiowain.jobseeker.fit.domain.RemotePolicy;

import java.util.List;

public record PreferencesDto(String homeMunicipality,
                             String acceptableMunicipalities,
                             RemotePolicy remotePolicy,
                             String dealBreakers,
                             List<CandidateLanguageDto> languages) {
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/dto/TriageRequest.java`:

```java
package se.caiowain.jobseeker.fit.api.dto;

import se.caiowain.jobseeker.fit.domain.TriageState;

public record TriageRequest(TriageState state, String note) {
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/dto/DeepFitDto.java`:

```java
package se.caiowain.jobseeker.fit.api.dto;

import java.time.Instant;
import java.util.List;

public record DeepFitDto(Long jobId, int coveragePercent, int requirementCount,
                         List<String> gaps, String modelUsed, Instant deepScoredAt) {
}
```

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/dto/PrescreenSummaryDto.java`:

```java
package se.caiowain.jobseeker.fit.api.dto;

import java.time.Instant;

public record PrescreenSummaryDto(int postings, int withMatches, Instant computedAt) {
}
```

- [ ] **Step 2: Write the failing controller test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/api/FitApiTest.java`:

```java
package se.caiowain.jobseeker.fit.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.FitFixtures;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.select.ModelUnavailableException;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionResult;
import se.caiowain.jobseeker.select.SelectionResult.RequirementSelection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FitApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobPostingRepository jobs;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobDeepFitRepository deepFits;
    @Autowired JobTriageRepository triages;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean OllamaSelectionClient selectionClient;

    private static final String DESCRIPTION =
            "Vi söker en utvecklare med erfarenhet av Java och REST-API:er.";

    private Long jobId;

    @BeforeEach
    void seed() {
        deepFits.deleteAllInBatch();
        triages.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        jobId = jobs.save(FitFixtures.posting(
                "api-1", "Utvecklare", DESCRIPTION, "Stockholm")).getId();

        CvProfile profile = FitFixtures.readyProfile(
                documents.save(FitFixtures.document("d".repeat(64))), "Java");
        FitFixtures.withBullets(profile, "Byggde REST-API:er i Java");
        profiles.saveAndFlush(profile);

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());
    }

    @Test
    void prescreenReportsWhatItCovered() throws Exception {
        mvc.perform(post("/api/fit/prescreen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postings").value(1));
    }

    @Test
    void deepScoringReturnsCoverageAndGaps() throws Exception {
        mvc.perform(post("/api/jobs/" + jobId + "/fit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coveragePercent").value(100))
                .andExpect(jsonPath("$.requirementCount").value(1));
    }

    @Test
    void aDeepScoreCanBeReadBackAndIsAbsentUntilOneIsRun() throws Exception {
        mvc.perform(get("/api/jobs/" + jobId + "/fit")).andExpect(status().isNotFound());

        mvc.perform(post("/api/jobs/" + jobId + "/fit")).andExpect(status().isOk());

        mvc.perform(get("/api/jobs/" + jobId + "/fit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coveragePercent").value(100));
    }

    @Test
    void anUnreachableModelIsFiveOhThreeNotAZeroScore() throws Exception {
        doThrow(new ModelUnavailableException("Could not reach the local model at :11434"))
                .when(selectionClient).select(anyString(), any());

        mvc.perform(post("/api/jobs/" + jobId + "/fit"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void prescreeningWithoutAnApprovedProfileIsFourOhNine() throws Exception {
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        mvc.perform(post("/api/fit/prescreen")).andExpect(status().isConflict());
    }

    @Test
    void scoringAnUnknownJobIsFourOhFour() throws Exception {
        mvc.perform(post("/api/jobs/999999/fit")).andExpect(status().isNotFound());
    }

    @Test
    void aTriageDecisionIsStored() throws Exception {
        mvc.perform(put("/api/jobs/" + jobId + "/triage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"DISMISSED\",\"note\":\"wrong stack\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DISMISSED"));

        assertThat(triages.findByJobPostingId(jobId).orElseThrow().getState())
                .isEqualTo(TriageState.DISMISSED);
    }

    @Test
    void anUnknownTriageStateIsRejectedAsABadRequest() throws Exception {
        mvc.perform(put("/api/jobs/" + jobId + "/triage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"MAYBE_LATER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preferencesRoundTripIncludingLanguages() throws Exception {
        mvc.perform(put("/api/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"homeMunicipality":"Stockholm",
                                 "acceptableMunicipalities":"Stockholm, Solna",
                                 "remotePolicy":"HYBRID_OK",
                                 "dealBreakers":"no on-call",
                                 "languages":[{"language":"sv","level":"CONVERSATIONAL"}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remotePolicy").value("HYBRID_OK"))
                .andExpect(jsonPath("$.languages[0].language").value("sv"))
                .andExpect(jsonPath("$.languages[0].level").value("CONVERSATIONAL"));
    }

    @Test
    void savingPreferencesTwiceReplacesLanguagesRatherThanAppending() throws Exception {
        String body = """
                {"remotePolicy":"ONSITE_ONLY",
                 "languages":[{"language":"sv","level":"NATIVE"}]}
                """;

        mvc.perform(put("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        mvc.perform(put("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON).content(body));

        mvc.perform(get("/api/preferences"))
                .andExpect(jsonPath("$.languages.length()").value(1));
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=FitApiTest
```

Expected: FAIL — the endpoints return 404 because the controllers do not exist.

- [ ] **Step 4: Write `PreferencesController`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/PreferencesController.java`:

```java
package se.caiowain.jobseeker.fit.api;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.fit.api.dto.CandidateLanguageDto;
import se.caiowain.jobseeker.fit.api.dto.PreferencesDto;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

    private final JobPreferencesRepository preferences;

    public PreferencesController(JobPreferencesRepository preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PreferencesDto get() {
        return toDto(preferences.findSingleton());
    }

    @PutMapping
    @Transactional
    public PreferencesDto save(@RequestBody PreferencesDto request) {
        JobPreferences prefs = preferences.findSingleton();
        prefs.setHomeMunicipality(request.homeMunicipality());
        prefs.setAcceptableMunicipalities(request.acceptableMunicipalities());
        if (request.remotePolicy() != null) {
            prefs.setRemotePolicy(request.remotePolicy());
        }
        prefs.setDealBreakers(request.dealBreakers());
        prefs.setUpdatedAt(Instant.now());

        // Replace wholesale. orphanRemoval on the association deletes the old rows, so a
        // second save cannot accumulate duplicate languages.
        prefs.getLanguages().clear();
        List<CandidateLanguageDto> languages =
                request.languages() == null ? List.of() : request.languages();
        int ordinal = 0;
        for (CandidateLanguageDto dto : languages) {
            CandidateLanguage language = new CandidateLanguage();
            language.setLanguage(dto.language());
            language.setLevel(dto.level());
            language.setOrdinal(ordinal++);
            prefs.addLanguage(language);
        }

        return toDto(preferences.save(prefs));
    }

    private static PreferencesDto toDto(JobPreferences prefs) {
        return new PreferencesDto(
                prefs.getHomeMunicipality(),
                prefs.getAcceptableMunicipalities(),
                prefs.getRemotePolicy(),
                prefs.getDealBreakers(),
                prefs.getLanguages().stream()
                        .map(l -> new CandidateLanguageDto(l.getLanguage(), l.getLevel()))
                        .toList());
    }
}
```

- [ ] **Step 5: Write `FitController`**

Create `backend/src/main/java/se/caiowain/jobseeker/fit/api/FitController.java`:

```java
package se.caiowain.jobseeker.fit.api;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.fit.DeepFitService;
import se.caiowain.jobseeker.fit.PrescreenService;
import se.caiowain.jobseeker.fit.PrescreenSummary;
import se.caiowain.jobseeker.fit.TriageService;
import se.caiowain.jobseeker.fit.api.dto.DeepFitDto;
import se.caiowain.jobseeker.fit.api.dto.PrescreenSummaryDto;
import se.caiowain.jobseeker.fit.api.dto.TriageRequest;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;
import se.caiowain.jobseeker.fit.domain.JobDeepFitGap;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;

@RestController
public class FitController {

    private final PrescreenService prescreen;
    private final DeepFitService deepFit;
    private final TriageService triage;
    private final JobDeepFitRepository deepFits;

    public FitController(PrescreenService prescreen, DeepFitService deepFit,
                         TriageService triage, JobDeepFitRepository deepFits) {
        this.prescreen = prescreen;
        this.deepFit = deepFit;
        this.triage = triage;
        this.deepFits = deepFits;
    }

    /** Cheap and corpus-wide. Works with Ollama stopped. */
    @PostMapping("/api/fit/prescreen")
    public PrescreenSummaryDto prescreen() {
        PrescreenSummary summary = prescreen.run();
        return new PrescreenSummaryDto(
                summary.postings(), summary.withMatches(), summary.computedAt());
    }

    /** Roughly 94 seconds on this hardware. The frontend shows a spinner saying so. */
    @PostMapping("/api/jobs/{id}/fit")
    public DeepFitDto score(@PathVariable Long id) {
        return toDto(deepFit.score(id));
    }

    @GetMapping("/api/jobs/{id}/fit")
    @Transactional(readOnly = true)
    public ResponseEntity<DeepFitDto> latest(@PathVariable Long id) {
        return deepFits.findFirstByJobPostingIdOrderByDeepScoredAtDesc(id)
                .map(fit -> ResponseEntity.ok(toDto(fit)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/jobs/{id}/triage")
    public TriageRequest decide(@PathVariable Long id, @RequestBody TriageRequest request) {
        JobTriage decision = triage.decide(id, request.state(), request.note());
        return new TriageRequest(decision.getState(), decision.getNote());
    }

    private static DeepFitDto toDto(JobDeepFit fit) {
        return new DeepFitDto(
                fit.getJobPosting().getId(),
                fit.getCoveragePercent(),
                fit.getRequirementCount(),
                fit.getGaps().stream().map(JobDeepFitGap::getText).toList(),
                fit.getModelUsed(),
                fit.getDeepScoredAt());
    }
}
```

- [ ] **Step 6: Run it — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=FitApiTest
```

Expected: PASS, all ten tests.

- [ ] **Step 7: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
cd ..
git add backend/src
git commit -m "feat: expose prescreen, deep fit, triage and preferences over REST"
```

---

## Task 10: Rank, filter and hide on the job list

**Files:**
- Modify: `api/JobQueryService.java`, `api/JobController.java`, `api/dto/JobSummaryDto.java`, `fit/repo/JobPrescreenRepository.java`, `fit/repo/JobTriageRepository.java`
- Test: `src/test/java/se/caiowain/jobseeker/fit/JobListFitTest.java`

**Interfaces:**
- Consumes: `JobPrescreen`, `JobTriage`, `GateVerdict`, `TriageState`, `FitFixtures`, `PrescreenService.run`, `TriageService.decide`.
- Produces: `JobQueryService.search(String query, String municipality, AtsVendor vendor, SourceId source, String sort, TriageState triage, boolean includeGateFailures, int page, int size) -> Page<JobPosting>`; a `JobSummaryDto` carrying fit fields;
  `JobPrescreenRepository.findByJobPostingIdIn(Collection<Long>)`, `JobTriageRepository.findByJobPostingIdIn(Collection<Long>)`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/se/caiowain/jobseeker/fit/JobListFitTest.java`:

```java
package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JobListFitTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired PrescreenService prescreen;
    @Autowired TriageService triage;
    @Autowired JobPostingRepository jobs;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobTriageRepository triages;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    private Long strongId;
    private Long weakId;
    private Long vetoedId;

    @BeforeEach
    void seed() {
        triages.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        CandidateLanguage sv = new CandidateLanguage();
        sv.setLanguage("sv");
        sv.setLevel(LanguageLevel.NATIVE);
        prefs.addLanguage(sv);
        prefs.setAcceptableMunicipalities("Stockholm");
        preferences.saveAndFlush(prefs);

        profiles.saveAndFlush(FitFixtures.readyProfile(
                documents.save(FitFixtures.document("e".repeat(64))),
                "Java", "Kubernetes", "Docker"));

        strongId = jobs.save(FitFixtures.posting("strong", "Plattformsingenjör",
                "Vi kör Java, Kubernetes och Docker i produktion.", "Stockholm")).getId();
        weakId = jobs.save(FitFixtures.posting("weak", "Utvecklare",
                "Vi kör Java i produktion.", "Stockholm")).getId();
        vetoedId = jobs.save(FitFixtures.posting("vetoed", "Konsult",
                "Vi kräver flytande engelska. Vi kör Java, Kubernetes och Docker.",
                "Stockholm")).getId();

        prescreen.run();
    }

    @Test
    void sortingByFitPutsTheAdNamingMostOfYourSkillsFirst() throws Exception {
        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(strongId))
                .andExpect(jsonPath("$.content[0].matchedSkillCount").value(3))
                .andExpect(jsonPath("$.content[1].id").value(weakId));
    }

    @Test
    void theMatchedSkillNamesTravelWithTheRowSoTheCountIsCheckable() throws Exception {
        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(jsonPath("$.content[0].matchedSkills").value(
                        org.hamcrest.Matchers.containsString("Kubernetes")));
    }

    @Test
    void gateFailuresAreHiddenByDefaultAndShownOnRequestWithTheirEvidence() throws Exception {
        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/jobs?sort=skills&includeGateFailures=true"))
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get("/api/jobs?includeGateFailures=true&q=Konsult"))
                .andExpect(jsonPath("$.content[0].languageGate").value("FAIL"))
                .andExpect(jsonPath("$.content[0].languageNote").value("flytande engelska"));
    }

    @Test
    void aDismissedJobDropsOutOfTheDefaultListAndCanBeAskedForBack() throws Exception {
        triage.decide(weakId, TriageState.DISMISSED, null);

        mvc.perform(get("/api/jobs"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/jobs?triage=DISMISSED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(weakId));
    }

    @Test
    void theTriageStateTravelsWithTheRow() throws Exception {
        triage.decide(strongId, TriageState.SHORTLISTED, null);

        mvc.perform(get("/api/jobs?triage=SHORTLISTED"))
                .andExpect(jsonPath("$.content[0].triage").value("SHORTLISTED"));
    }

    @Test
    void postingsWithNoPrescreenRowStillAppear() throws Exception {
        // A job ingested after the last prescreen must not vanish from the list — the
        // fit join is a LEFT join for exactly this case.
        jobs.save(FitFixtures.posting("fresh", "Nyinkommen",
                "Vi kör Java.", "Stockholm"));

        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void theDefaultSortIsStillNewestFirst() throws Exception {
        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=JobListFitTest
```

Expected: FAIL — `matchedSkillCount` is missing from the response and `sort` is ignored.

- [ ] **Step 3: Add the batch lookups to the repositories**

In `backend/src/main/java/se/caiowain/jobseeker/fit/repo/JobPrescreenRepository.java`, add:

```java
    java.util.List<se.caiowain.jobseeker.fit.domain.JobPrescreen>
            findByJobPostingIdIn(java.util.Collection<Long> jobPostingIds);
```

In `backend/src/main/java/se/caiowain/jobseeker/fit/repo/JobTriageRepository.java`, add:

```java
    java.util.List<se.caiowain.jobseeker.fit.domain.JobTriage>
            findByJobPostingIdIn(java.util.Collection<Long> jobPostingIds);
```

- [ ] **Step 4: Widen `JobSummaryDto`**

Replace `backend/src/main/java/se/caiowain/jobseeker/api/dto/JobSummaryDto.java` entirely:

```java
package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.TriageState;

import java.time.Instant;

/**
 * The fit fields are nullable on purpose: a posting ingested since the last prescreen has
 * no row yet, and it must still be listed rather than disappearing.
 */
public record JobSummaryDto(
        Long id, String title, String employerName, String municipality,
        AtsVendor atsVendor, String applyUrl, Instant publishedAt, Instant lastSeenAt,
        Integer matchedSkillCount, String matchedSkills,
        GateVerdict languageGate, String languageNote,
        GateVerdict locationGate, Boolean deadlinePassed,
        TriageState triage) {
}
```

- [ ] **Step 5: Rewrite `JobQueryService`**

Replace `backend/src/main/java/se/caiowain/jobseeker/api/JobQueryService.java` entirely:

```java
package se.caiowain.jobseeker.api;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobPostingSource;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class JobQueryService {

    private final JobPostingRepository postings;

    public JobQueryService(JobPostingRepository postings) {
        this.postings = postings;
    }

    /**
     * @param sort                 {@code "skills"} ranks by how many of your skills the ad
     *                             names; anything else keeps newest-first
     * @param triage               show only this decision; null shows everything except
     *                             dismissed
     * @param includeGateFailures  when false, postings a gate vetoed are sorted out of view.
     *                             They are never deleted, and this flag brings them back.
     */
    public Page<JobPosting> search(String query, String municipality, AtsVendor vendor,
                                   SourceId source, String sort, TriageState triage,
                                   boolean includeGateFailures, int page, int size) {

        Specification<JobPosting> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // LEFT, always: a posting ingested since the last prescreen has no fit row and
            // must still be listed. An inner join here would silently hide new jobs.
            Join<Object, Object> fit = root.join("prescreen", JoinType.LEFT);
            Join<Object, Object> decision = root.join("triage", JoinType.LEFT);

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern),
                        cb.like(cb.lower(root.get("employerName")), pattern)));
            }
            if (municipality != null && !municipality.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("municipality")),
                        municipality.toLowerCase()));
            }
            if (vendor != null) {
                predicates.add(cb.equal(root.get("atsVendor"), vendor));
            }
            if (source != null) {
                // EXISTS rather than a join plus distinct. DISTINCT would collide with the
                // ORDER BY below, because PostgreSQL requires ordering expressions to appear
                // in the select list of a SELECT DISTINCT.
                Subquery<Long> sub = criteriaQuery.subquery(Long.class);
                Root<JobPostingSource> sourceRoot = sub.from(JobPostingSource.class);
                sub.select(cb.literal(1L)).where(cb.and(
                        cb.equal(sourceRoot.get("jobPosting"), root),
                        cb.equal(sourceRoot.get("source"), source)));
                predicates.add(cb.exists(sub));
            }

            if (triage != null) {
                predicates.add(cb.equal(decision.get("state"), triage));
            } else {
                // Undecided jobs have no row at all, so the null case is the common one.
                predicates.add(cb.or(
                        decision.get("state").isNull(),
                        cb.notEqual(decision.get("state"), TriageState.DISMISSED)));
            }

            if (!includeGateFailures) {
                predicates.add(cb.or(fit.get("languageGate").isNull(),
                        cb.notEqual(fit.get("languageGate"), GateVerdict.FAIL)));
                predicates.add(cb.or(fit.get("locationGate").isNull(),
                        cb.notEqual(fit.get("locationGate"), GateVerdict.FAIL)));
                predicates.add(cb.or(fit.get("deadlinePassed").isNull(),
                        cb.isFalse(fit.get("deadlinePassed"))));
            }

            // Ordering lives here rather than in the Pageable because it has to reference
            // the LEFT join above; a Sort on the nested path would make Hibernate build its
            // own inner join and drop every posting without a prescreen row.
            //
            // Spring Data reuses this Specification for its count query, and PostgreSQL
            // rejects ORDER BY in a count, so skip it when the result type is a Long.
            Class<?> resultType = criteriaQuery.getResultType();
            if (resultType != Long.class && resultType != long.class) {
                if ("skills".equals(sort)) {
                    criteriaQuery.orderBy(
                            cb.desc(cb.coalesce(fit.<Integer>get("matchedSkillCount"), 0)),
                            cb.desc(root.get("publishedAt")));
                } else {
                    criteriaQuery.orderBy(cb.desc(root.get("publishedAt")));
                }
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };

        // Unsorted Pageable: the Specification above owns the ordering.
        return postings.findAll(spec, PageRequest.of(page, size));
    }
}
```

- [ ] **Step 6: Rewrite `JobController`'s list method and summary mapping**

In `backend/src/main/java/se/caiowain/jobseeker/api/JobController.java`, add the constructor
dependencies and replace `list` and `toSummary`. The rest of the file — `detail` and
`toDetail` — is unchanged.

Add imports:

```java
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
```

Replace the fields and constructor:

```java
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
```

Replace `list`:

```java
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

        // Two extra queries for the whole page, rather than two per row. The inverse
        // associations exist so the query above can join; they are deliberately not
        // navigated here, because a lazy to-one still costs a select each.
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
```

Replace `toSummary`:

```java
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
```

- [ ] **Step 7: Run the fit list test — expect PASS**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw -q test -Dtest=JobListFitTest
```

Expected: PASS, all seven tests.

- [ ] **Step 8: Run the whole suite and commit**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
```

Expected: PASS. `JobControllerTest` from Slice 1 must still be green — the source filter
changed from a join plus distinct to an EXISTS subquery, which is equivalent; if that test
fails, the subquery is wrong, not the test.

```bash
cd .. && git add backend/src
git commit -m "feat: rank, filter and hide vetoed postings on the job list"
```

---

## Task 11: Frontend types, client and the preferences page

The preferences are hand-edited and rarely change, so this is a plain form with a save
button — no autosave, no optimistic updates.

**Files:**
- Create: `frontend/src/fitTypes.ts`, `frontend/src/api/fitClient.ts`, `frontend/src/pages/PreferencesPage.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `GET`/`PUT /api/preferences`, `POST /api/fit/prescreen` (Task 9).
- Produces: `Preferences`, `CandidateLanguage`, `LanguageLevel`, `RemotePolicy`, `GateVerdict`, `TriageState`, `DeepFit` types; `fetchPreferences`, `savePreferences`, `runPrescreen`, `scoreJob`, `fetchDeepFit`, `setTriage` client functions; route `/preferences`.

- [ ] **Step 1: Write the types**

Create `frontend/src/fitTypes.ts`:

```ts
export type LanguageLevel =
  | 'NONE'
  | 'BASIC'
  | 'CONVERSATIONAL'
  | 'PROFESSIONAL'
  | 'FLUENT'
  | 'NATIVE'

export type RemotePolicy = 'ONSITE_ONLY' | 'HYBRID_OK' | 'REMOTE_ONLY'

/** UNKNOWN is not a pass — the gate could not read a requirement, so nothing is claimed. */
export type GateVerdict = 'PASS' | 'FLAG' | 'FAIL' | 'UNKNOWN'

export type TriageState = 'NEW' | 'SHORTLISTED' | 'DISMISSED'

export const LANGUAGE_LEVELS: LanguageLevel[] = [
  'NONE',
  'BASIC',
  'CONVERSATIONAL',
  'PROFESSIONAL',
  'FLUENT',
  'NATIVE',
]

export interface CandidateLanguage {
  language: string
  level: LanguageLevel
}

export interface Preferences {
  homeMunicipality: string | null
  acceptableMunicipalities: string | null
  remotePolicy: RemotePolicy
  dealBreakers: string | null
  languages: CandidateLanguage[]
}

export interface PrescreenSummary {
  postings: number
  withMatches: number
  computedAt: string
}

export interface DeepFit {
  jobId: number
  coveragePercent: number
  requirementCount: number
  /** Requirements nothing in your CV answers. Quoted from the ad, never a claim about you. */
  gaps: string[]
  modelUsed: string | null
  deepScoredAt: string
}
```

- [ ] **Step 2: Write the API client**

Create `frontend/src/api/fitClient.ts`:

```ts
import type { DeepFit, Preferences, PrescreenSummary, TriageState } from '../fitTypes'

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

export function fetchPreferences(): Promise<Preferences> {
  return json<Preferences>('/api/preferences')
}

export function savePreferences(preferences: Preferences): Promise<Preferences> {
  return json<Preferences>('/api/preferences', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(preferences),
  })
}

/** Deterministic and corpus-wide. Runs in well under a second, with Ollama stopped. */
export function runPrescreen(): Promise<PrescreenSummary> {
  return json<PrescreenSummary>('/api/fit/prescreen', { method: 'POST' })
}

/** Runs a local model over one ad. One to two minutes on this hardware. */
export function scoreJob(jobId: number): Promise<DeepFit> {
  return json<DeepFit>(`/api/jobs/${jobId}/fit`, { method: 'POST' })
}

/** Resolves to null when the job has not been deep-scored — a 404 here is expected. */
export async function fetchDeepFit(jobId: number): Promise<DeepFit | null> {
  const response = await fetch(`/api/jobs/${jobId}/fit`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<DeepFit>
}

export function setTriage(jobId: number, state: TriageState, note?: string): Promise<unknown> {
  return json(`/api/jobs/${jobId}/triage`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state, note: note ?? null }),
  })
}
```

- [ ] **Step 3: Write the preferences page**

Create `frontend/src/pages/PreferencesPage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchPreferences, savePreferences } from '../api/fitClient'
import type { CandidateLanguage, LanguageLevel, Preferences, RemotePolicy } from '../fitTypes'
import { LANGUAGE_LEVELS } from '../fitTypes'

const EMPTY: Preferences = {
  homeMunicipality: '',
  acceptableMunicipalities: '',
  remotePolicy: 'HYBRID_OK',
  dealBreakers: '',
  languages: [],
}

export function PreferencesPage() {
  const [prefs, setPrefs] = useState<Preferences>(EMPTY)
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchPreferences()
      .then((loaded) => setPrefs({ ...EMPTY, ...loaded }))
      .catch((e: Error) => setError(e.message))
  }, [])

  const set = (patch: Partial<Preferences>) => setPrefs({ ...prefs, ...patch })

  const setLanguage = (index: number, patch: Partial<CandidateLanguage>) => {
    const languages = prefs.languages.map((l, i) => (i === index ? { ...l, ...patch } : l))
    set({ languages })
  }

  const save = async () => {
    setStatus(null)
    setError(null)
    try {
      const saved = await savePreferences(prefs)
      setPrefs({ ...EMPTY, ...saved })
      setStatus('Saved. Re-run the ranking on the jobs page to apply it.')
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/profile" className="text-slate-600 hover:underline">My CV profile</Link>
        <span className="font-medium text-slate-900">Preferences</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Preferences</h1>
      <p className="mt-1 text-sm text-slate-600">
        What you will accept. These are your decisions, kept separately from your CV, so
        re-uploading a CV never overwrites them.
      </p>

      <section className="mt-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Languages</h2>
        <p className="mt-1 text-xs text-slate-500">
          An ad demanding a language that is not listed here is vetoed outright. One demanding
          a level above yours is flagged, not vetoed — bars like “flytande” vary by employer.
        </p>

        <ul className="mt-3 space-y-2">
          {prefs.languages.map((language, index) => (
            <li key={index} className="flex gap-2">
              <input
                type="text"
                value={language.language}
                placeholder="sv"
                onChange={(e) => setLanguage(index, { language: e.target.value })}
                className="w-24 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
              />
              <select
                value={language.level}
                onChange={(e) => setLanguage(index, { level: e.target.value as LanguageLevel })}
                className="flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
              >
                {LANGUAGE_LEVELS.map((level) => (
                  <option key={level} value={level}>{level}</option>
                ))}
              </select>
              <button
                onClick={() =>
                  set({ languages: prefs.languages.filter((_, i) => i !== index) })
                }
                className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>

        <button
          onClick={() =>
            set({ languages: [...prefs.languages, { language: '', level: 'CONVERSATIONAL' }] })
          }
          className="mt-3 rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
        >
          Add a language
        </button>
      </section>

      <section className="mt-8 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Location</h2>

        <label className="block">
          <span className="text-sm text-slate-700">Acceptable municipalities</span>
          <input
            type="text"
            value={prefs.acceptableMunicipalities ?? ''}
            placeholder="Stockholm, Solna, Sundbyberg"
            onChange={(e) => set({ acceptableMunicipalities: e.target.value })}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
          />
          <span className="mt-1 block text-xs text-slate-500">
            Comma-separated. Leave empty to check nothing.
          </span>
        </label>

        <label className="block">
          <span className="text-sm text-slate-700">Remote policy</span>
          <select
            value={prefs.remotePolicy}
            onChange={(e) => set({ remotePolicy: e.target.value as RemotePolicy })}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
          >
            <option value="ONSITE_ONLY">On site only</option>
            <option value="HYBRID_OK">Hybrid is fine</option>
            <option value="REMOTE_ONLY">Remote only — location is irrelevant</option>
          </select>
        </label>

        <label className="block">
          <span className="text-sm text-slate-700">Deal-breakers</span>
          <textarea
            value={prefs.dealBreakers ?? ''}
            rows={3}
            placeholder="Notes for yourself; not evaluated automatically."
            onChange={(e) => set({ dealBreakers: e.target.value })}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
          />
        </label>
      </section>

      <div className="mt-8 flex items-center gap-3">
        <button
          onClick={save}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700"
        >
          Save preferences
        </button>
        {status && <span className="text-sm text-slate-600">{status}</span>}
      </div>

      {error && (
        <p className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Add the route**

In `frontend/src/App.tsx`, add the import and the route:

```tsx
import { PreferencesPage } from './pages/PreferencesPage'
```

```tsx
        <Route path="/preferences" element={<PreferencesPage />} />
```

- [ ] **Step 5: Commit**

The build is verified in Task 12, which adds the components this page's navigation links to.

```bash
git add frontend/src
git commit -m "feat: add the preferences page and fit API client"
```

---

## Task 12: Fit on the job list and the job detail page

**Files:**
- Create: `frontend/src/components/GateBadge.tsx`, `frontend/src/components/SkillMatchChip.tsx`, `frontend/src/components/TriageButtons.tsx`
- Modify: `frontend/src/types.ts`, `frontend/src/components/JobFilters.tsx`, `frontend/src/pages/JobList.tsx`, `frontend/src/pages/JobDetail.tsx`

**Interfaces:**
- Consumes: `fitClient` functions and `fitTypes` (Task 11); `GET /api/jobs` fit fields (Task 10).
- Produces: the finished UI. This is the last task.

- [ ] **Step 1: Widen the shared types**

In `frontend/src/types.ts`, add the import at the top:

```ts
import type { GateVerdict, TriageState } from './fitTypes'
```

Extend `JobSummary` with the nullable fit fields — nullable because a posting ingested since
the last ranking has no fit row yet and must still be listed:

```ts
export interface JobSummary {
  id: number
  title: string
  employerName: string | null
  municipality: string | null
  atsVendor: string
  applyUrl: string | null
  publishedAt: string | null
  lastSeenAt: string | null
  matchedSkillCount: number | null
  matchedSkills: string | null
  languageGate: GateVerdict | null
  languageNote: string | null
  locationGate: GateVerdict | null
  deadlinePassed: boolean | null
  triage: TriageState | null
}
```

Extend `JobFilters`:

```ts
export interface JobFilters {
  q?: string
  municipality?: string
  vendor?: string
  source?: string
  sort?: string
  triage?: string
  includeGateFailures?: boolean
  page?: number
  size?: number
}
```

`fetchJobs` in `api/client.ts` already serialises every defined key, so it needs no change.

- [ ] **Step 2: Write `SkillMatchChip`**

Create `frontend/src/components/SkillMatchChip.tsx`:

```tsx
interface Props {
  matched: number | null
  names: string | null
}

/**
 * A count of facts, not a score. The names are on the title attribute so the number is
 * always checkable — that is the whole point of counting rather than scoring.
 */
export function SkillMatchChip({ matched, names }: Props) {
  if (matched === null) {
    return <span className="text-xs text-slate-400">not ranked</span>
  }
  const strong = matched >= 3
  return (
    <span
      title={names ?? 'No skills from your profile appear in this ad'}
      className={`rounded-full px-2 py-0.5 text-xs font-medium ${
        strong ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-700'
      }`}
    >
      {matched} skill{matched === 1 ? '' : 's'}
    </span>
  )
}
```

- [ ] **Step 3: Write `GateBadge`**

Create `frontend/src/components/GateBadge.tsx`:

```tsx
import type { GateVerdict } from '../fitTypes'

interface Props {
  label: string
  verdict: GateVerdict | null
  note?: string | null
}

const STYLES: Record<GateVerdict, string> = {
  PASS: 'bg-emerald-50 text-emerald-700',
  FLAG: 'bg-amber-50 text-amber-800',
  FAIL: 'bg-red-50 text-red-700',
  UNKNOWN: 'bg-slate-100 text-slate-500',
}

/**
 * PASS is deliberately not rendered: a list of green ticks buries the two verdicts that
 * actually need reading. UNKNOWN is shown, because "could not tell" is information.
 */
export function GateBadge({ label, verdict, note }: Props) {
  if (verdict === null || verdict === 'PASS') return null

  const title =
    verdict === 'UNKNOWN'
      ? `${label}: nothing recognised in this ad — not a pass`
      : note ?? label

  return (
    <span title={title} className={`rounded px-2 py-0.5 text-xs font-medium ${STYLES[verdict]}`}>
      {label} {verdict}
    </span>
  )
}
```

- [ ] **Step 4: Write `TriageButtons`**

Create `frontend/src/components/TriageButtons.tsx`:

```tsx
import { useState } from 'react'
import { setTriage } from '../api/fitClient'
import type { TriageState } from '../fitTypes'

interface Props {
  jobId: number
  state: TriageState | null
  onChanged: () => void
}

export function TriageButtons({ jobId, state, onChanged }: Props) {
  const [busy, setBusy] = useState(false)

  const decide = async (next: TriageState) => {
    setBusy(true)
    try {
      await setTriage(jobId, next)
      onChanged()
    } finally {
      setBusy(false)
    }
  }

  const style = (active: boolean) =>
    `rounded-md border px-2 py-1 text-xs transition disabled:opacity-50 ${
      active ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-300 hover:bg-slate-50'
    }`

  return (
    <div className="flex gap-2">
      <button
        disabled={busy}
        onClick={() => decide(state === 'SHORTLISTED' ? 'NEW' : 'SHORTLISTED')}
        className={style(state === 'SHORTLISTED')}
      >
        Shortlist
      </button>
      <button
        disabled={busy}
        onClick={() => decide(state === 'DISMISSED' ? 'NEW' : 'DISMISSED')}
        className={style(state === 'DISMISSED')}
      >
        Dismiss
      </button>
    </div>
  )
}
```

- [ ] **Step 5: Extend `JobFilters`**

In `frontend/src/components/JobFilters.tsx`, add three controls after the existing source
`<select>`, inside the same wrapper `<div>`:

```tsx
      <select
        value={value.sort ?? ''}
        onChange={(e) => set({ sort: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      >
        <option value="">Newest first</option>
        <option value="skills">Most skills matched</option>
      </select>
      <select
        value={value.triage ?? ''}
        onChange={(e) => set({ triage: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      >
        <option value="">Undecided and shortlisted</option>
        <option value="SHORTLISTED">Shortlisted only</option>
        <option value="DISMISSED">Dismissed only</option>
      </select>
      <label className="flex items-center gap-2 text-sm text-slate-600">
        <input
          type="checkbox"
          checked={value.includeGateFailures ?? false}
          onChange={(e) => set({ includeGateFailures: e.target.checked })}
        />
        Show vetoed
      </label>
```

- [ ] **Step 6: Extend `JobList`**

In `frontend/src/pages/JobList.tsx`:

Add the imports:

```tsx
import { runPrescreen } from '../api/fitClient'
import { GateBadge } from '../components/GateBadge'
import { SkillMatchChip } from '../components/SkillMatchChip'
import { TriageButtons } from '../components/TriageButtons'
```

Add the ranking state beside the existing state:

```tsx
  const [ranking, setRanking] = useState(false)
  const [rankMessage, setRankMessage] = useState<string | null>(null)
```

Add the handler after `load`:

```tsx
  const rank = async () => {
    setRanking(true)
    setRankMessage(null)
    try {
      const summary = await runPrescreen()
      setRankMessage(
        `Ranked ${summary.postings} postings — ${summary.withMatches} name at least one of your skills.`,
      )
      load()
    } catch (e) {
      setRankMessage((e as Error).message)
    } finally {
      setRanking(false)
    }
  }
```

Add the preferences link to the nav:

```tsx
        <Link to="/preferences" className="text-slate-600 hover:underline">Preferences</Link>
```

Add the rank button directly above `<JobFilters …>`:

```tsx
      <div className="mb-4 flex items-center gap-3">
        <button
          onClick={rank}
          disabled={ranking}
          className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-50"
        >
          {ranking ? 'Ranking…' : 'Rank against my CV'}
        </button>
        {rankMessage && <span className="text-sm text-slate-600">{rankMessage}</span>}
      </div>
```

Replace the contents of the list item's `<li>` with the fit-aware row:

```tsx
          <li key={job.id} className="py-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <Link
                  to={`/jobs/${job.id}`}
                  className="text-base font-medium text-slate-900 hover:underline"
                >
                  {job.title}
                </Link>
                <p className="mt-0.5 truncate text-sm text-slate-600">
                  {job.employerName ?? 'Unknown employer'}
                  {job.municipality ? ` · ${job.municipality}` : ''}
                </p>
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  <SkillMatchChip matched={job.matchedSkillCount} names={job.matchedSkills} />
                  <GateBadge label="Language" verdict={job.languageGate} note={job.languageNote} />
                  <GateBadge label="Location" verdict={job.locationGate} />
                  {job.deadlinePassed && (
                    <span className="rounded bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700">
                      Deadline passed
                    </span>
                  )}
                </div>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-2">
                <SourceBadge label={job.atsVendor} />
                <TriageButtons jobId={job.id} state={job.triage} onChanged={load} />
              </div>
            </div>
          </li>
```

- [ ] **Step 7: Add the deep score to `JobDetail`**

In `frontend/src/pages/JobDetail.tsx`, add the imports:

```tsx
import { fetchDeepFit, scoreJob } from '../api/fitClient'
import type { DeepFit } from '../fitTypes'
```

Add the state beside the existing state:

```tsx
  const [fit, setFit] = useState<DeepFit | null>(null)
  const [scoring, setScoring] = useState(false)
  const [fitError, setFitError] = useState<string | null>(null)
```

Add the loader beside the existing effects:

```tsx
  useEffect(() => {
    if (!id) return
    fetchDeepFit(Number(id)).then(setFit).catch(() => setFit(null))
  }, [id])
```

Add the handler beside `runTailor`:

```tsx
  const runScore = async () => {
    if (!id) return
    setScoring(true)
    setFitError(null)
    try {
      setFit(await scoreJob(Number(id)))
    } catch (e) {
      setFitError((e as Error).message)
    } finally {
      setScoring(false)
    }
  }
```

Add this section directly above the `Description` section:

```tsx
      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Requirement coverage
        </h2>

        {fit ? (
          <>
            <p className="text-sm text-slate-800">
              Your own bullets answer <strong>{fit.coveragePercent}%</strong> of the{' '}
              {fit.requirementCount} requirement{fit.requirementCount === 1 ? '' : 's'} this ad
              states.
            </p>
            {fit.gaps.length > 0 && (
              <div className="mt-3">
                <p className="text-sm text-slate-600">Nothing in your CV answers these:</p>
                <ul className="mt-2 space-y-1">
                  {fit.gaps.map((gap) => (
                    <li
                      key={gap}
                      className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900"
                    >
                      {gap}
                    </li>
                  ))}
                </ul>
                <p className="mt-2 text-xs text-slate-500">
                  Quoted from the ad. This gap is the honest distance between you and the job.
                </p>
              </div>
            )}
          </>
        ) : (
          <button
            onClick={runScore}
            disabled={scoring}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
          >
            {scoring ? 'Reading the ad… (1–2 min)' : 'Score this job'}
          </button>
        )}

        {fitError && (
          <p className="mt-2 rounded-md bg-red-50 p-3 text-sm text-red-700">{fitError}</p>
        )}
      </section>
```

- [ ] **Step 8: Build and typecheck**

```bash
cd frontend
npm run build
```

Expected: a clean TypeScript build. A `JobSummary` field the backend does not send would fail
here, so a green build also confirms Task 10's DTO matches these types.

- [ ] **Step 9: Run the whole backend suite one last time**

```bash
cd backend && export JAVA_HOME=/usr/lib/jvm/default
./mvnw test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add frontend/src
git commit -m "feat: show skill matches, gate vetoes and triage on the job list"
```

---

## Verification against the spec's success criteria

Run through these by hand once the tasks are done. Numbers match Section 10 of the spec.

| # | Criterion | Where it is proved |
|---|---|---|
| 1 | `./mvnw test` passes with no network calls | Task 12 Step 9; `AbstractIntegrationTest` points Ollama and JobTech at dead ports |
| 2 | Slices 1, 2a, 2b work with Ollama stopped; prescreen, gates, ranking and triage work with Ollama stopped | `PrescreenService` and `TriageService` have no model dependency; verify by hand with Ollama down |
| 3 | A prescreen over the full corpus completes in under a second | Hand check against the real database: `POST /api/fit/prescreen` |
| 4 | A Swedish posting naming `Java` and `Kubernetes` matches those skills | `PrescreenServiceTest.englishSkillTermsMatchInsideASwedishAd` |
| 5 | `phraseto_tsquery` does not match *Spring* and *Boot* in unrelated positions | `PrescreenServiceTest.aMultiWordSkillNeedsItsWordsAdjacent` |
| 6 | An ad requiring an unlisted language is vetoed, and the UI shows the phrase | `GateEvaluatorTest.aLanguageYouDoNotSpeakAtAllIsAHardVeto`, `JobListFitTest.gateFailuresAreHiddenByDefaultAndShownOnRequestWithTheirEvidence`, `GateBadge` |
| 7 | An ad stating no language requirement is UNKNOWN, not PASS | `GateEvaluatorTest.anAdStatingNoLanguageRequirementIsUnknownNotPass` |
| 8 | A prescreen re-run leaves triage and deep fit unchanged | `TriageServiceTest.aPrescreenRebuildLeavesDecisionsAlone`, `DeepFitServiceTest.aPrescreenRebuildLeavesADeepScoreAlone`, `FitSchemaMigrationTest.nothingPreservedDependsOnTheDisposableTable` |
| 9 | A dismissed job does not reappear in the default list | `JobListFitTest.aDismissedJobDropsOutOfTheDefaultListAndCanBeAskedForBack` |
| 10 | Deep scoring returns verbatim requirements; coverage matches a hand calculation | `DeepFitServiceTest.coverageIsComputedInJavaFromCountedRows`, `aPhraseAbsentFromTheAdIsRejectedAfterOneRetryAndNothingIsPersisted` |
| 11 | Requirements matching no bullet are visible and highlighted | `DeepFitServiceTest.theGapsAreTheRequirementsNothingSupports`, the amber list in `JobDetail` |
| 12 | No test, and no code path reachable from a test, touches `apply_url` | `grep -rn "apply_url\|getApplyUrl" backend/src/main/java/se/caiowain/jobseeker/fit` returns nothing |
