# Slice 2d — Job Fit Triage

**Status:** approved design, ready for implementation planning
**Date:** 2026-08-30
**Project:** AgenticJobSeeker — Autonomous AI Job Application Engine
**Predecessors:** `2026-08-27-job-discovery-spine-design.md` (Slice 1),
`2026-08-27-cv-profile-ingestion-design.md` (Slice 2a),
`2026-08-28-extractive-tailoring-design.md` (Slice 2b)

---

## 1. Context

Slice 1 delivers ~600 deduplicated Swedish IT postings. Slice 2a delivers an approved CV
profile. Slice 2b tailors a package for **one job you have already chosen**, and its spec
says so explicitly: *"No match-scoring across the whole job list; you pick the job, the
system does the rest."*

That deferral is now the system's largest gap. Discovery works, tailoring works, and there
is no answer to the only question standing between them: **of these 600, which ones?**

The slice number reflects build order, not pipeline order. In the pipeline this sits
*before* 2b: triage a corpus, shortlist a handful, tailor those.

### Goal

Rank every discovered posting against your profile, veto the ones you are not eligible for,
and record what you decided — without asking a model for an opinion about you.

### Non-goals

No automatic application (Slice 3). No PDF rendering (2c). No behavioural or
career-alignment scoring — see Section 2.3. No scheduled or bulk deep scoring. No change to
how jobs are discovered, merged or tailored.

---

## 2. Decisions taken, and the evidence behind them

### 2.1 Prior art: `MadsLorentzen/ai-job-search`

The scoring framework adapted here comes from
[`MadsLorentzen/ai-job-search`](https://github.com/MadsLorentzen/ai-job-search) (MIT), a
Claude Code job-application framework with a documented result — 69 applications, 20 first
interviews, one hire. Its `04-job-evaluation.md` is the best-specified version of the thing
Slice 2b deferred.

Three of its ideas are adopted: **hard gates that run before scoring**, the **language
gate** as a first-class veto, and **"silence is not permission"** — an unparsed requirement
is unknown, never a pass.

Three are deliberately not adopted, and Section 2.3 explains why: its 0–100 per-dimension
scores, its behavioural and career-alignment dimensions, and its drafter–reviewer
generation pipeline.

Its architecture is not portable here at all. It is markdown prompts, Bun CLIs for Danish
job boards and LaTeX templates, with files as the database. Nothing in it links into a
Spring Boot application. What transfers is domain knowledge, not code.

### 2.2 Speed decides the shape

Slice 2b measured extractive selection at **94 seconds per job** on this hardware. Over
~600 postings that is **15.6 hours**. A model pass across the corpus is therefore not
available at any quality.

So the slice is staged: a deterministic prescreen ranks everything in milliseconds, and the
model runs only on postings you promote.

### 2.3 Nothing in this slice produces a judgement

The upstream framework's output is `Technical Skills: 72/100`. That number is a model's
opinion about a person, and Slice 2b exists to make exactly that unrepresentable. A 7B
local model's `72` would also be noise: Spike 2 showed the model matching *"avslutad
eftergymnasial utbildning"* against all six bullets it was given.

Neither stage scores.

| Stage | Output | What it is |
|---|---|---|
| Prescreen | *"names 8 of your 22 skills: Java, Spring Boot, Kubernetes, …"* | A count of facts, each checkable by eye |
| Gates | PASS / FLAG / FAIL / UNKNOWN **plus the phrase that decided it** | A veto with its evidence attached |
| Deep | `coverage_percent`, and the requirements matching nothing | The 2b formula, computed in Java |

The model's output surface stays what Spike 2 measured it can do: verbatim phrases from the
ad and integers pointing at bullets. **This slice introduces no new prompt, no new guard and
no new model failure mode** — it reuses 2b's selection call whole.

The two dimensions this drops — behavioural fit and career alignment, 45% of the upstream
weighting — need career goals, energising and draining tasks, and a behavioural read. None
of that exists in `cv_profile`, and none of it is extractable from a CV PDF. Scoring them
would mean inventing the inputs.

### 2.4 Lexical matching is sufficient in this market

The known weakness of full-text matching is paraphrase, and across languages that is
normally fatal — a Swedish ad saying *"backend-utveckling"* will not match an English CV
saying *"server-side development"*.

It is not fatal here, for a reason specific to Swedish IT postings: **skill terms stay
English inside Swedish ads.** A Stockholm posting written entirely in Swedish still says
Java, Spring Boot, Kubernetes, React, PostgreSQL, Docker, AWS. Those are `cv_skill` rows,
matched verbatim, whatever language either document is in.

Titles and responsibilities will match poorly. Skills will match well, and skills are what
the technical dimension measures. The residual semantic gap is what the deep stage exists to
close.

### 2.5 Resulting decisions

| Decision | Choice |
|---|---|
| Prescreen | Postgres full-text over the whole corpus, `'simple'` config |
| Prescreen output | Count of matched skills — never a synthetic 0–100 |
| Deep scoring | On demand, one job, reusing 2b's selection call and guards |
| Gates | Deterministic, in Java, always quoting the phrase that decided |
| Gate failures | Sorted out of view, never deleted, always overridable |
| New model paths | None |

---

## 3. Architecture

### 3.1 Pipeline

```
after each ingest run (and on demand)
  POST /api/fit/prescreen
    → require a cv_profile with status READY        else 409
    → one native FTS query over job_posting × cv_skill
    → GateEvaluator          language, location, deadline
    → truncate and rebuild job_prescreen            nothing else is touched

you read the ranked list, shortlist or dismiss
  PUT /api/jobs/{id}/triage

for a shortlisted job you want measured
  POST /api/jobs/{id}/fit
    → SelectionPromptBuilder    job description + numbered profile bullets
    → OllamaSelectionClient     ChatClient, format=json → SelectionResult
    → SelectionGuard            the same three guards as 2b
    → Coverage                  requirements with evidence ÷ total
    → persist coverage_percent and the unmet requirements
```

### 3.2 The prescreen

`job_posting` gains one generated column and a GIN index:

```sql
ALTER TABLE job_posting ADD COLUMN search_tsv tsvector
  GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, ''))
  ) STORED;
CREATE INDEX idx_job_posting_search_tsv ON job_posting USING GIN (search_tsv);
```

Additive and derived. Postgres maintains it, so it cannot go stale — which is why this is
preferable to a side table the prescreen has to remember to update.

**`'simple'`, not `'swedish'`, for two reasons.** A generated column requires an
`IMMUTABLE` expression, which rules out selecting the config from the row's `language`
column. And it is the correct config regardless: skill terms are proper nouns, and stemming
damages them — the `swedish` config mangles *Spring*, the `english` config mangles *React*.
`'simple'` lowercases and tokenises, which is exactly and only what matching *Kubernetes*
inside a Swedish ad requires.

One query per run:

```sql
SELECT p.id, s.name
FROM job_posting p
JOIN cv_skill s ON s.cv_profile_id = :profileId
WHERE p.search_tsv @@ phraseto_tsquery('simple', s.name)
```

`phraseto_tsquery` treats *"Spring Boot"* as a phrase rather than two loose tokens. Java
groups the `(jobId, skillName)` pairs into `job_prescreen` rows. Roughly 600 × 22 index probes —
milliseconds.

Ordering is matched-skill count descending, then `published_at` descending. There is no
normalisation to 0–100: with a single profile the divisor is constant, so it would change no
ordering while inventing a precision the count does not have.

### 3.3 The gates

**Language.** `LanguageRequirementDetector` is pure: ad text in, `(language, level, quoted
phrase)` out.

| Ad wording | Read as |
|---|---|
| `flytande svenska`, `obehindrat på svenska`, `fluent Swedish` | sv, FLUENT |
| `svenska i tal och skrift`, `goda kunskaper i svenska` | sv, PROFESSIONAL |
| `engelska i tal och skrift`, `fluent English` | en, FLUENT / PROFESSIONAL |

`GateEvaluator` compares that against `candidate_language`:

| Situation | Verdict |
|---|---|
| Required language absent from `candidate_language` entirely | **FAIL** |
| Present, but the ad's bar is above your recorded level | **FLAG** — score it, tell you the gap |
| Present, at or below your recorded level | **PASS** |
| No pattern matched | **UNKNOWN** |

**UNKNOWN is not PASS.** Pattern lists are never complete, and a gate that silently passes
everything it failed to parse is worse than no gate.

**Location.** `municipality` against `acceptable_municipalities`, with `remote_policy` as an
override. **Deadline.** `deadline_at` in the past.

**A gate never deletes.** A FAIL sorts the posting out of the default view and displays the
phrase that caused it. You may know something about your own situation that the profile does
not record, so the verdict is always visible and always overridable.

### 3.4 Components

```
select/                       moved up out of tailor/ — see 3.5
  SelectionPromptBuilder      pure
  OllamaSelectionClient       the only component that calls a model
  SelectionGuard              pure
  Coverage                    pure; the formula, shared with 2b

fit/
  LanguageRequirementDetector pure — ad text → (language, level, phrase)
  GateEvaluator               pure — detector output + preferences → verdicts
  PrescreenService            the native query; rebuilds job_prescreen
  DeepFitService              select → guard → Coverage → persist
  TriageService               state transitions
  domain/ repo/ api/          following the profile/ and tailor/ layout
```

### 3.5 One refactor, folded in

`tailor/select/` moves to `select/`, and `TailoringPromptBuilder` becomes
`SelectionPromptBuilder`.

Without it, `fit` imports from `tailor`, which reads backwards — fit scoring is upstream of
tailoring, not downstream of it. After the move both packages depend on `select`, which is
what is actually true: extractive requirement matching has two consumers.

Mechanical, roughly six files, no logic changed, covered by the existing 2b tests.

---

## 4. Data model

Flyway `V7`. Six new tables plus the generated column in 3.2.

**Every table name states its lifecycle**, because the lifecycles are opposite and mixing
them destroys expensive work.

```
job_preferences       singleton (id = 1). Things you decide, not things your
                      CV says — so re-extracting a CV can never clobber them.
                      home_municipality,
                      acceptable_municipalities TEXT (comma-separated, as in
                        search_criteria.municipality_names),
                      remote_policy: ONSITE_ONLY | HYBRID_OK | REMOTE_ONLY,
                      deal_breakers TEXT, updated_at

candidate_language    job_preferences_id FK, language, level, ordinal
                      level: NONE < BASIC < CONVERSATIONAL
                             < PROFESSIONAL < FLUENT < NATIVE

job_prescreen         DISPOSABLE. Truncated and rebuilt on every prescreen run.
                      Per (job_posting, cv_profile).
                      job_posting_id FK, cv_profile_id FK,
                      matched_skill_count INT,
                      matched_skills TEXT (comma-separated names, for display),
                      language_gate, language_note,
                      location_gate, deadline_passed

job_deep_fit          PRESERVED. 94 seconds of work per row.
                      Per (job_posting, cv_profile).
                      job_posting_id FK, cv_profile_id FK,
                      coverage_percent INT, model_used, deep_scored_at

job_deep_fit_gap      PRESERVED. The requirements that matched no bullet.
                      job_deep_fit_id FK, text, ordinal

job_triage            PRESERVED. Your decision, per job_posting, never
                      auto-recomputed.
                      job_posting_id FK UNIQUE,
                      state: NEW | SHORTLISTED | DISMISSED,
                      decided_at, note
```

A prescreen run truncates and rebuilds `job_prescreen` and touches nothing else. It must be
structurally incapable of destroying a dismissal or a deep score — not merely careful not
to. Re-uploading a CV invalidates fit, not judgement: `job_deep_fit` is keyed on the profile
id, so a new profile produces new rows and leaves the old ones as harmless history.

Re-running a deep score on the same (job, profile) pair replaces its `job_deep_fit` row and
its gaps. There is no approval state here to protect, so unlike 2b there is no `409`.

`level` is an ordered enum rather than free text because a deterministic gate needs a
comparison. The upstream framework reasons about levels in prose; a veto cannot.

Indexes: unique on `job_prescreen (job_posting_id, cv_profile_id)`, unique on
`job_deep_fit (job_posting_id, cv_profile_id)`, unique on `job_triage (job_posting_id)`,
`job_prescreen (matched_skill_count DESC)`, `job_deep_fit_gap (job_deep_fit_id)`.

---

## 5. REST API

| Method | Path | Purpose |
|---|---|---|
| `GET`/`PUT` | `/api/preferences` | The singleton, with its languages |
| `POST` | `/api/fit/prescreen` | Rebuild every fit row — `409` unless a profile is `READY` |
| `POST` | `/api/jobs/{id}/fit` | Deep score one job, synchronous, ~94 s |
| `PUT` | `/api/jobs/{id}/triage` | `NEW` \| `SHORTLISTED` \| `DISMISSED` |

`GET /api/jobs` gains `sort=skills`, `triage=`, and `includeGateFailures=` (default
`false`). `JobSummaryDto` gains the matched-skill count, the matched skill names, the gate
verdicts and the triage state.

---

## 6. Frontend

- **`/preferences`** — languages with level dropdowns, acceptable municipalities, remote
  policy, deal-breakers.
- **`JobFilters`** — sort by skills matched, filter by triage state, a toggle to include
  gate failures.
- **`JobList` row** — an `8/22 skills` chip listing the matched names on hover, a gate badge
  (amber FLAG, red FAIL, grey UNKNOWN), and shortlist / dismiss actions.
- **`JobDetail`** — a *Score this job* action beside the existing *Tailor for this job*.
  Renders coverage, the matched requirements and the unmet ones. A gate verdict always shows
  the phrase that produced it.

Deep scoring shows the same one-to-two-minute spinner as tailoring, because it is the same
call.

---

## 7. Error handling

| Condition | Response |
|---|---|
| Prescreen or deep score with no `READY` CV profile | `409` — "Approve your CV profile first" |
| Job id unknown | `404` |
| Ollama unreachable during a deep score | `503` — names the base URL and the model |
| Guard A/B violation after one retry | `422`; no `job_deep_fit` row written |
| Triage state not in the enum | `400` |

**The prescreen never needs a model.** With Ollama stopped, discovery, prescreening, gates,
ranking and triage all work normally; only deep scoring returns `503`.

> **Vocabulary.** `READY` is a *profile* status (2a). `APPROVED` is an *application* status
> (2b). `SHORTLISTED` is a *triage* state, introduced here, and means only "worth my
> attention" — it asserts nothing about an application.

---

## 8. Testing strategy

Test-driven, and **no test calls Ollama or any network host.**

- **`LanguageRequirementDetector`** — pure, table-driven over real Swedish ad phrasings,
  including ads that state no requirement at all (must yield UNKNOWN, not PASS).
- **`GateEvaluator`** — pure: the full verdict table, including a language absent from
  `candidate_language` failing, and a higher bar flagging rather than failing.
- **`PrescreenService`** — Testcontainers against real Postgres FTS. This is the slice's
  highest-value test: it proves the Section 2.4 assumption by matching `Kubernetes` and
  `Spring Boot` inside a Swedish-language ad, and proves `phraseto_tsquery` does not match
  *Spring* and *Boot* separately.
- **`DeepFitService`** — stubbed `ChatClient`; guard rejection writes no coverage.
- **`Coverage`** — pure; the same fixtures as 2b, asserting the two slices cannot drift.
- **Controllers** — MockMvc plus Testcontainers, as in Slices 1, 2a and 2b.
- **Migration** — asserts the `V7` tables, the generated column, both unique constraints,
  and that a prescreen rebuild leaves `job_triage` and `job_deep_fit` rows intact.

**Safety.** No code path in this slice reads, navigates or submits `apply_url`. The
no-real-applications constraint is satisfied structurally rather than by assertion.

---

## 9. Technical stack changes

None. No new dependency, no new model, no new service. Postgres full-text search is already
present in the database this project runs.

---

## 10. Success criteria

1. `./mvnw test` passes with no network calls.
2. The application starts and Slices 1, 2a and 2b work with Ollama stopped; prescreening,
   gates, ranking and triage all work with Ollama stopped.
3. A prescreen over the full corpus completes in under one second.
4. A Swedish-language posting naming `Java` and `Kubernetes` matches those `cv_skill` rows.
5. `phraseto_tsquery('simple', 'Spring Boot')` does not match an ad containing *Spring* and
   *Boot* in unrelated positions.
6. An ad requiring a language absent from `candidate_language` is vetoed, and the UI shows
   the phrase that vetoed it.
7. An ad stating no language requirement is UNKNOWN, not PASS.
8. A prescreen re-run leaves every `job_triage` and `job_deep_fit` row unchanged.
9. A dismissed job does not reappear in the default list after the next ingest run.
10. Deep scoring a real job returns requirements quoted verbatim from that job's
    description, and `coverage_percent` matches a hand calculation on a seeded fixture.
11. Requirements matching no bullet are visible and highlighted.
12. No test, and no code path reachable from a test, touches `apply_url`.
