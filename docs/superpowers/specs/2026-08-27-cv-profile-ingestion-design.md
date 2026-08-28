# Slice 2a — CV Profile Ingestion

**Status:** approved design, ready for implementation planning
**Date:** 2026-08-27
**Project:** AgenticJobSeeker — Autonomous AI Job Application Engine
**Predecessor:** `2026-08-27-job-discovery-spine-design.md` (Slice 1, delivered)

---

## 1. Context

Slice 1 delivered the discovery spine: 597 Swedish IT postings in PostgreSQL, deduplicated
across JobTech, Teamtailor and Varbi, browsable in a dashboard. It deliberately deferred
`UserProfile` — so the system currently knows about jobs but nothing about the applicant.

Slice 2 (Tailoring) was scoped as AI CV/cover-letter agents, PDF rendering and an approval
queue. Adding PDF ingestion to it made it at least as large as Slice 1, so it was split:

| Sub-slice | Delivers |
|---|---|
| **2a — Profile** (this spec) | Upload CV PDF → extract text → LLM structures it → review and correct it |
| 2b — Tailoring | CV/cover-letter agents, translation, PDF rendering, approval queue |

### Goal

Your real CV is in the system as structured, reviewed, trustworthy data that Slice 2b can
tailor from.

### Non-goals for Slice 2a

No tailoring, no cover letters, no PDF *output*, no approval queue, no job matching, no
translation, no authentication. Multi-profile support is out: the system holds one master
profile, and the newest upload is the current one.

---

## 2. Decisions taken

| Decision | Choice | Why |
|---|---|---|
| CV input | Upload PDF; LLM extracts to a structured profile you then correct | Least manual effort, while still giving 2b per-bullet structure to work with |
| Language | Store as extracted, with a language tag | One source of truth; 2b translates when a posting demands the other language |
| Extraction | One-shot structured call **plus** a deterministic hallucination guard | Layout robustness is not the real risk; quietly invented employers and shifted dates are |
| Execution | Synchronous upload → extract | One-time action for a single user; an async job table would have no beneficiary |
| Model | `claude-opus-5`, adaptive thinking, configurable | Quality-sensitive extraction; cost is a per-upload one-off |
| Integration | Spring AI `spring-ai-starter-model-anthropic:2.0.1` | Verified Boot-4 native (depends on `spring-boot-starter:4.1.1`); the brief specified Spring AI |

---

## 3. Architecture

### 3.1 Pipeline

```
POST /api/profile/upload  (multipart PDF)
  → CvUploadService      store bytes + SHA-256      (same file re-uploaded is idempotent)
  → PdfTextExtractor     PDFBox 3.0.8 → raw text, stored
  → CvProfileExtractor   Spring AI ChatClient, structured output → ExtractedProfile
  → ExtractionValidator  per-record verified / unverified
  → CvProfileService     persist, status = NEEDS_REVIEW
  → dashboard review and correction → status = READY
```

### 3.2 Units

Five components, each understandable and testable in isolation:

- **`CvUploadService`** — accepts the multipart file, validates type and size, computes the
  SHA-256, persists `cv_document`. Depends on the repository only.
- **`PdfTextExtractor`** — an interface with a PDFBox implementation. An interface because
  2b may need other input formats, and because tests need a deterministic stand-in.
- **`CvProfileExtractor`** — the **only** component that calls an LLM. Takes raw text,
  returns an `ExtractedProfile` record. Everything model-specific lives here.
- **`ExtractionValidator`** — pure functions, no I/O, no Spring. Compares extracted literals
  against the source text.
- **`CvProfileService`** — persistence and status transitions.

### 3.3 The hallucination guard

The failure mode that matters is not a mangled layout — it is the model inventing an
employer or shifting a date into something plausible, which is exactly the error a person
skims past when reviewing their own CV.

`ExtractionValidator` normalises the source text (lowercase, collapse whitespace, strip
punctuation) and asserts that every extracted **employer name**, **job title** and **date
literal** occurs within it. Records failing the check are persisted with `verified = false`
and a `verification_notes` string naming the offending fields; the review UI renders them
amber.

The check is deliberately one-directional. It cannot prove the extraction is complete — only
that what it produced is present in the source. Detecting *omissions* is what human review
is for, which is why raw text sits beside the form.

Bullet text is **not** guarded this way: extraction legitimately reflows and merges wrapped
lines, so a substring assertion would produce constant false positives. Bullets are reviewed
by eye against the raw text.

### 3.4 Raw text retention

`cv_document.extracted_text` is kept permanently. It buys three things: side-by-side review,
re-extraction without re-upload when a prompt or model changes (`POST /api/profile/reextract`),
and a cheap retry path after a failed call.

### 3.5 Structured output caveat

Spring AI's `ChatClient.entity(...)` drives structured output by injecting a JSON schema into
the prompt — a weaker guarantee than Anthropic's native strict `output_config.format`.
Implementation will use native structured outputs if Spring AI 2.0.1 exposes them for the
Anthropic model, and fall back to `.entity(...)` otherwise. This is to be verified against
the artifact during implementation, not assumed. The validator is what makes the system safe
under either outcome.

---

## 4. Data model

Flyway `V5` (Slice 1 ended at `V4`).

```
cv_document      id, filename, content_type, size_bytes, sha256 UNIQUE,
                 content BYTEA, extracted_text TEXT, uploaded_at

cv_profile       id, cv_document_id FK, full_name, headline, email, phone,
                 location, summary, language, status, model_used,
                 extracted_at, reviewed_at

cv_experience    id, cv_profile_id FK, employer, title, start_date, end_date,
                 is_current, location, ordinal, verified, verification_notes

cv_experience_bullet
                 id, cv_experience_id FK, text, ordinal

cv_education     id, cv_profile_id FK, institution, degree, field_of_study,
                 start_date, end_date, ordinal, verified, verification_notes

cv_skill         id, cv_profile_id FK, name, category, ordinal
```

`cv_profile.status` is one of `NEEDS_REVIEW`, `READY`, `EXTRACTION_FAILED`.

**Dates are `VARCHAR(32)`, stored exactly as the CV writes them** ("2019", "Mar 2020–present").
Parsing into `DATE` would invent precision the source does not have and would defeat the
substring guard.

**The PDF is stored in `bytea`**, not on disk: one user, one backup, no path management. Upload
is capped at 10 MB.

**Bullets are a table, not a blob**, because selecting and reordering individual bullets is
precisely what Slice 2b does.

Indexes: `cv_document.sha256` (unique), `cv_profile.cv_document_id`,
`cv_experience.cv_profile_id`, `cv_experience_bullet.cv_experience_id`,
`cv_education.cv_profile_id`, `cv_skill.cv_profile_id`.

---

## 5. REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/profile/upload` | multipart PDF → extract → `201` with the profile |
| `GET` | `/api/profile` | current (newest) profile, `404` if none |
| `PUT` | `/api/profile` | save reviewer corrections |
| `POST` | `/api/profile/reextract` | re-run extraction from stored text |
| `POST` | `/api/profile/approve` | `NEEDS_REVIEW` → `READY` |
| `GET` | `/api/profile/source-text` | raw extracted text, `text/plain` |

### 5.1 What "current profile" means

Every upload of a **new** file creates a new `cv_document` and a new `cv_profile`. The one
with the highest `id` is the current profile, and it is what every endpoint above without an
explicit id operates on. Earlier profiles are retained rather than deleted — they cost
nothing and preserve a record of what was extracted before a prompt or model changed.

Re-uploading a file whose SHA-256 already exists is **idempotent**: no new document, no new
extraction, no LLM call. The endpoint returns `200` with the existing profile rather than
`201`, so an accidental double-upload cannot silently discard reviewer corrections.

`PUT /api/profile` and `POST /api/profile/approve` act on the current profile. Editing a
profile already in `READY` is allowed and returns it to `READY` — corrections are not a
one-way gate.

Approval does **not** require every unverified flag to be resolved. The flag is advisory:
it directs attention, and the reviewer decides. Approving with flags still set is a
legitimate outcome (a genuinely reflowed employer name, for example), and the flag is
retained on the record afterwards.

---

## 6. Frontend

A `/profile` route, reached from a nav link on the job list.

- **No profile yet** — an upload control accepting a single PDF.
- **After extraction** — two columns: raw extracted text on the left (monospace, scrollable),
  the editable structured form on the right. Unverified experience and education rows render
  amber with their `verification_notes` shown.
- **Approve profile** button transitions the profile to `READY`.

Editing covers the fields 2b consumes: identity, summary, experience (employer, title, dates,
bullets), education, skills. Bullets can be edited, reordered and deleted.

---

## 7. Error handling

Each failure gets a distinct, actionable response rather than a generic 500:

| Condition | Response |
|---|---|
| Not a PDF, or larger than 10 MB | `400` with the reason |
| PDF has no text layer (scanned image) | `422` — "no extractable text — is this a scan?" |
| LLM call fails or times out | Profile persisted as `EXTRACTION_FAILED`, raw text retained, recoverable via `reextract` |
| No API key configured | `503` with a plain message; the application still starts |

The last row matters: no Anthropic credential exists on the development machine, so the
application must start and serve Slice 1 normally with extraction unavailable.

---

## 8. Testing strategy

Test-driven, and **no test calls a live LLM or network**.

- **`ExtractionValidator`** — pure unit tests, the highest-value in the slice. Includes a case
  where the model returns an employer absent from the source and must be flagged unverified,
  and a case proving reflowed bullet text is not falsely flagged.
- **`PdfTextExtractor`** — runs against a PDF **generated in-test with PDFBox**. Deterministic,
  and it keeps a real CV out of the repository.
- **`CvProfileExtractor`** — a stubbed `ChatClient` returns canned JSON; asserts the mapping
  into `ExtractedProfile` and the failure path.
- **Controllers** — MockMvc plus Testcontainers PostgreSQL, following Slice 1.
- **Migration** — asserts the `V5` tables and constraints exist, following `SchemaMigrationTest`.

---

## 9. Technical stack additions

Added to the existing Java 21 / Spring Boot 4.1.1 / PostgreSQL / Flyway stack:

- `spring-ai-starter-model-anthropic:2.0.1` (verified to depend on `spring-boot-starter:4.1.1`)
- `org.apache.pdfbox:pdfbox:3.0.8`

Not in this slice: PDF *rendering* (openhtmltopdf), Playwright.

---

## 10. Success criteria

1. Uploading a real CV PDF produces a structured profile in PostgreSQL.
2. Extracted employers, titles and dates are verified against the source; anything not found
   is flagged `unverified` rather than silently trusted.
3. The dashboard shows raw text and structured form side by side, with unverified rows marked.
4. Corrections persist, and approving sets the profile to `READY`.
5. Re-extraction works from stored text without re-uploading the PDF.
6. With no API key configured, the application starts and Slice 1 keeps working; upload
   returns `503`.
7. `./mvnw test` passes with no live LLM or network calls.
