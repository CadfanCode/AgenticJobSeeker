# Slice 2b — Extractive Tailoring

**Status:** approved design, ready for implementation planning
**Date:** 2026-08-28
**Project:** AgenticJobSeeker — Autonomous AI Job Application Engine
**Predecessors:** `2026-08-27-job-discovery-spine-design.md` (Slice 1),
`2026-08-27-cv-profile-ingestion-design.md` (Slice 2a)

---

## 1. Context

Slice 1 delivered 597 deduplicated Swedish IT postings. Slice 2a delivered CV ingestion:
upload a PDF, extract it with a model, verify the extraction against the source, review and
approve. Slice 2b turns those two into a per-job application package.

The original Slice 2 scope — tailoring agents, translation, PDF rendering and an approval
queue — was split when it reached Slice 1's size:

| Sub-slice | Delivers |
|---|---|
| **2b — Extractive tailoring** (this spec) | Requirement→evidence matching, tailored CV, letter skeleton, approval queue |
| 2c — Rendering | PDF output: page layout, embedded fonts for åäö, download |

### Goal

For a job you choose, produce an honest application package assembled entirely from
sentences you wrote.

### Non-goals

No PDF rendering (2c). No automatic submission (Slice 3). No bulk or scheduled generation.
No translation — see Section 3.5. No match-scoring across the whole job list; you pick the
job, the system does the rest.

---

## 2. Decisions taken, and the evidence behind them

The provider changed during design. The project must cost nothing to run, so generation
moved from the Anthropic API to **Ollama running locally**. Two throwaway spikes on the
actual hardware decided the architecture.

### 2.1 Hardware reality

| Property | Value |
|---|---|
| RAM | 14 GB total, ~6.8 GB available |
| GPU | AMD Cezanne integrated Vega — **not a supported Ollama target**; CPU-only |
| CPU | 16 threads |
| Model that fits | 7–8B quantized (`qwen2.5:7b-instruct`, Q4_K_M, 4.7 GB) |

### 2.2 Spike 1 — prose generation: rejected

Given a real Swedish posting (`job_posting` id 73, Academic Work, 2,999 chars) and a sample
profile, the model was asked to write a Swedish cover letter.

- **216 seconds**, 2.6 tok/s, 548 output tokens.
- **Swedish was not publishable.** The opening — *"Kvittar du mina erfarenheter…"* — is
  meaningless; *"har jag ledit över en monolith"* is not a word; *"mitt ansökan"* is the
  wrong gender; the sign-off *"Högt hopp om att lyckas med din genomgång!"* is gibberish.
- **It fabricated experience.** It claimed *"Min erfarenhet av att hantera teknisk support
  som en 2nd line support till externa partners…"*. There is no support experience in the
  profile — the phrase was lifted from the job ad and reattributed to the candidate.

The last point is disqualifying on its own: a cover letter asserting experience the
candidate does not have is a false statement to an employer, not a quality defect.

### 2.3 Spike 2 — extractive selection: adopted

The same model was asked to return **only** bullet IDs and requirement phrases copied from
the ad, in JSON.

- **94 seconds**, 156 output tokens, valid JSON on the first attempt.
- **5 of 5 requirement phrases were verbatim substrings of the ad** — zero fabrication.
- Matches were sensible: *"Har erfarenhet av webbutveckling"* → the Spring/REST bullet;
  *"Har god förståelse API-design"* → the REST and developer-API bullets. The ranking put
  API work first, correct for the role.
- One weakness: *"avslutad eftergymnasial utbildning"* was matched to **all six** bullets.
  Over-broad matching is therefore an explicit guard (Section 3.3).

### 2.4 Resulting decisions

| Decision | Choice |
|---|---|
| Provider | Ollama, local, `qwen2.5:7b-instruct`, configurable |
| Generation style | **Extractive only** — the model emits IDs and quoted ad phrases, never candidate-facing prose |
| Trigger | On demand, one job at a time |
| Slice 2a | Its extractor moves from Anthropic to Ollama, so nothing needs an API key |
| Execution | Synchronous, with a 1–2 minute spinner |
| Translation | Out of scope |

---

## 3. Architecture

### 3.1 The guarantee

Everything the employer would see is assembled in Java from rows already in your profile.
The model's entire output surface is:

```json
{
  "requirements": [ { "text": "<verbatim phrase from the ad>", "bulletIds": [4, 6] } ],
  "rankedBulletIds": [4, 6, 3, 1, 2, 5]
}
```

It cannot emit a sentence about you, because the only thing it can say about you is an
integer that points at something you already wrote.

### 3.2 Pipeline

```
POST /api/jobs/{id}/tailor
  → require a CV profile with status READY          else 409
  → TailoringPromptBuilder   job description + numbered profile bullets
  → OllamaSelectionClient    ChatClient, format=json → SelectionResult
  → SelectionGuard           validate; one retry, then fail visibly
  → ApplicationAssembler     deterministic assembly from profile rows
  → persist TailoredApplication, status = DRAFT
  → review, write your prose, approve
```

### 3.3 The guards

Three checks, all deterministic, no model involved:

- **Guard A — ID validity.** Every `bulletId` must exist in the current profile. Unknown
  IDs are a hard rejection.
- **Guard B — requirement verbatim.** Every `requirement.text`, normalised (lowercase,
  collapsed whitespace, punctuation stripped), must be a substring of the equally
  normalised job description. These are the ad's words, quoted; they are never claims
  about the candidate.
- **Guard C — over-broad match.** A requirement matching more than `max-bullets-per-requirement`
  (default 3) bullets is kept but flagged `over_broad`, and rendered as a warning rather
  than as evidence. This is the observed failure mode from Spike 2, not a hypothetical.

A violation of A or B rejects the whole result and retries once. A second failure stores
the application as `GENERATION_FAILED` with `raw_model_output` retained, so the failure is
debuggable rather than mysterious.

### 3.4 Components

- **`TailoringPromptBuilder`** — pure. Job description + numbered bullets → prompt strings.
- **`OllamaSelectionClient`** — the only component that calls a model. Returns
  `SelectionResult`.
- **`SelectionGuard`** — pure, no I/O. The highest-value tests in the slice.
- **`ApplicationAssembler`** — pure. `SelectionResult` + profile → the assembled package.
- **`TailoringService`** — orchestration, persistence, status transitions.

### 3.5 Why translation is out

Translating an English bullet into Swedish means generating new text about the candidate,
which is precisely what Section 2.2 disqualified. The tailored CV therefore stays in the
CV's language, quoted requirements stay in the ad's, and the letter skeleton is bilingual
until the human writes the connecting prose.

Swedish output remains possible later by either route — a stronger paid model, or a Swedish
version of the profile in Slice 2a — but neither is smuggled into this slice.

---

## 4. Data model

Flyway `V6`.

```
tailored_application  id, job_posting_id FK, cv_profile_id FK, status,
                      model_used, coverage_percent, generated_at, reviewed_at,
                      letter_prose TEXT, raw_model_output TEXT

application_requirement
                      id, tailored_application_id FK, text, ordinal,
                      over_broad BOOLEAN

application_evidence  id, application_requirement_id FK,
                      cv_experience_bullet_id BIGINT NULL,
                      bullet_text TEXT NOT NULL, ordinal
```

**Evidence stores both the bullet foreign key and a text snapshot.** The key preserves
traceability; the snapshot makes an approved application immutable. Without it, editing
your CV would silently rewrite applications you had already approved, and deleting a
bullet would gut them. The FK is nullable and `ON DELETE SET NULL` for exactly that reason.

**`coverage_percent`** is computed in Java, not by the model: requirements with at least one
evidence row ÷ total requirements. Requirements with **zero** evidence are the most valuable
output in the slice — they name the gap between you and the job.

`status`: `DRAFT`, `APPROVED`, `DISCARDED`, `GENERATION_FAILED`.
One application per (job, profile) pair. Re-tailoring **replaces** an application in
`DRAFT` or `GENERATION_FAILED`. An application in `APPROVED` is **not** replaced: the
request returns `409` and asks you to discard it first, so a re-run cannot silently destroy
prose you already wrote and approved. A `DISCARDED` application is replaced freely.

Indexes: `tailored_application (job_posting_id)`, unique on
`tailored_application (job_posting_id, cv_profile_id)`,
`application_requirement (tailored_application_id)`,
`application_evidence (application_requirement_id)`.

---

## 5. REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/jobs/{jobId}/tailor` | Generate the package — `201`; `409` unless a profile is `READY`, or if an `APPROVED` application already exists |
| `GET` | `/api/jobs/{jobId}/application` | Application for that job, `404` if none |
| `GET` | `/api/applications` | Approval queue, paged, newest first |
| `GET` | `/api/applications/{id}` | Full detail with requirements and evidence |
| `PUT` | `/api/applications/{id}/letter` | Save the prose you wrote |
| `POST` | `/api/applications/{id}/approve` | `DRAFT` → `APPROVED` |
| `DELETE` | `/api/applications/{id}` | Discard |

---

## 6. Frontend

- **Job detail** gains a *"Tailor for this job"* button, replaced by a link to the package
  once one exists.
- **`/applications`** — the queue: job title, employer, coverage %, status, generated date.
- **`/applications/{id}`** — the review screen, in three parts:
  1. **Requirement → evidence.** Each requirement with its supporting bullets. Requirements
     with no evidence render **amber**; over-broad matches render as a caution.
  2. **Tailored CV.** Your experiences and bullets, filtered and reordered for this job.
  3. **Letter skeleton.** Greeting, evidence bullets, and an editable box for your prose.
- A **coverage bar** at the top, and an **Approve** action.

Generation shows a spinner stating it takes one to two minutes, because on this hardware
it does.

---

## 7. Error handling

| Condition | Response |
|---|---|
| No `READY` CV profile | `409` — "Approve your CV profile first" |
| Re-tailoring over an `APPROVED` application | `409` — "Discard the approved application first" |
| Job id unknown | `404` |
| Ollama unreachable | `503` — names the base URL and the model |
| Guard A/B violation after one retry | `422`; stored `GENERATION_FAILED` with raw output |
| Model returns malformed JSON after one retry | `422`, same handling |

The application must start and Slices 1 and 2a must work normally when Ollama is not
running; only tailoring becomes unavailable.

> **Vocabulary.** `READY` is a *profile* status, from Slice 2a. `APPROVED` is an
> *application* status, introduced here. They are deliberately different words: tailoring
> requires a `READY` profile and produces an application that you later mark `APPROVED`.

---

## 8. Testing strategy

Test-driven, and **no test calls Ollama or any network host.**

- **`SelectionGuard`** — pure unit tests: unknown bullet id rejected; requirement absent
  from the ad rejected; a requirement matching four bullets flagged `over_broad`;
  case/whitespace/punctuation differences accepted; the real Spike 2 payload accepted.
- **`ApplicationAssembler`** — pure: ordering follows `rankedBulletIds`; coverage maths;
  every assembled bullet is byte-identical to a profile bullet.
- **`TailoringPromptBuilder`** — pure: bullets are numbered stably; the description is included.
- **`TailoringService`** — stubbed `ChatClient`; covers retry-then-fail and the 409 path.
- **Controllers** — MockMvc plus Testcontainers, as in Slices 1 and 2a.
- **Migration** — asserts `V6` tables, the unique constraint and the nullable evidence FK.

A regression test asserts the Spike 1 failure cannot recur: given a model response
containing a requirement phrase absent from the ad, the guard rejects it.

---

## 9. Technical stack changes

- **Add** `spring-ai-starter-model-ollama:2.0.1` (verified present in the 2.0.1 BOM).
- **Remove** `spring-ai-starter-model-anthropic`; migrate `CvProfileExtractor` to Ollama,
  dropping the `ANTHROPIC_API_KEY` requirement from Slice 2a.
- Ollama runs locally at `http://localhost:11434`, model `qwen2.5:7b-instruct`, both
  configurable in `application.yml`.
- Ollama itself is installed user-locally under `~/.local/ollama` — no sudo, nothing
  outside the home directory.

---

## 10. Success criteria

1. `./mvnw test` passes with no network calls.
2. The application starts and Slices 1 and 2a work with Ollama stopped.
3. Tailoring with no profile in status `READY` returns `409`.
4. Tailoring a real job returns requirements quoted verbatim from that job's description.
5. Every bullet in the assembled package is byte-identical to a bullet in the profile.
6. A model response naming a bullet id that does not exist is rejected, not persisted.
7. Requirements with no matching evidence are visible and highlighted in the UI.
8. Coverage percent matches a hand calculation on a seeded fixture.
9. Approving freezes the package: later profile edits do not alter it.
