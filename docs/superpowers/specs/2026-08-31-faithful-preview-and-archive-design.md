# Slice 2c — Faithful Preview and Immutable Archive

**Status:** approved design, ready for implementation planning
**Date:** 2026-08-31
**Project:** AgenticJobSeeker — Autonomous AI Job Application Engine
**Predecessors:** `2026-08-27-cv-profile-ingestion-design.md` (Slice 2a),
`2026-08-28-extractive-tailoring-design.md` (Slice 2b),
`2026-08-30-job-fit-triage-design.md` (Slice 2d)

---

## 1. Context

Slice 2b assembles an application from sentences the candidate wrote: requirement→evidence
matching, a tailored CV, a letter skeleton, and an approval queue. Its spec deferred rendering
to "2c — Rendering: PDF output: page layout, embedded fonts for åäö, download", and that slice
was never built. So today an application exists only as rows in a database and a React page.

Two things follow from that, and this slice fixes both.

**Nothing can be reviewed as a document.** The approval gate exists, but what it approves is a
web page, not the artifact an employer would receive. Approving something you have not seen in
its delivered form is not a real gate.

**Nothing is kept.** `tailored_application` is working state — 2b explicitly allows a re-tailor
to replace a `DRAFT` or `DISCARDED` application outright. An interview months later has nothing
to refer back to, and the job ad itself is not safe either: `JobMergeService` overwrites
`job_posting.description` on every ingest run, keeping the richest text it has seen, and
employers delete postings.

### Goal

See the application exactly as its recipient will, approve it deliberately, and keep an
immutable record of what was approved — including the ad it answered.

### Non-goals

No automatic submission (Slice 3). No translation — Slice 2b Section 3.5 rules it out and
nothing here changes that. No editing of a frozen archive. No template customisation; one CV
layout and one letter layout. No bulk approval.

---

## 2. Decisions taken, and the evidence behind them

### 2.1 The recipient receives a PDF, so the archive must hold PDF bytes

The `apply_url` values in this database point at Teamtailor and Varbi web forms. Those forms
take an uploaded CV file and a cover letter that is either uploaded or pasted into a textarea.
An archive of the *inputs* to a document is therefore not a record of what the employer saw; an
archive of the rendered file is.

### 2.2 One rendering engine, or the guarantee is unenforceable

The requirement is that the preview is faithful. Any design with two renderers — one for the
screen, one for the file — can only be faithful by coincidence, and the failure mode is silent:
a preview that looks correct beside a PDF that is not.

| Option | Verdict |
|---|---|
| **HTML + headless Chromium** | **Adopted.** The preview endpoint returns the same HTML string the renderer prints. Preview and artifact cannot diverge, because there is one implementation. |
| Pure-Java `openhtmltopdf` | Rejected. No browser dependency, but roughly CSS 2.1 support and a different engine from the preview — the drift this slice exists to prevent. |
| LaTeX | Rejected. Excellent typography, but adds a full TeX toolchain to a project needing only Java, Node and Docker, and the preview would be a compiled artifact rather than a live page. |

### 2.3 The browser dependency is owed one slice later regardless

Slice 3 drives Teamtailor and Varbi forms with a headless browser. Adopting Playwright here is
paying that cost one slice early, not adding a new one — and it keeps the project's constraints
intact: local, free, no cloud, no API key.

### 2.4 The archive outlives the application it came from

This is the same lifecycle split Slice 2d established between `job_prescreen` (truncated and
rebuilt) and `job_triage` (preserved), and for the same reason.

| Table | Lifecycle |
|---|---|
| `tailored_application` | Working state. 2b replaces it freely on re-tailor. |
| `application_archive` | Preserved. Never rewritten, never cascade-deleted. |

`application_archive` therefore holds its own copies of everything needed to read it, and its
foreign keys are **nullable with `ON DELETE SET NULL`** — traceability, never dependency. An
archive a later action can delete is not an archive.

### 2.5 Resulting decisions

| Decision | Choice |
|---|---|
| Renderer | Headless Chromium via Playwright, driven from Java |
| Source of truth for layout | One HTML string per document, shared by preview and renderer |
| Deliverable | Two PDFs (CV, letter) plus the letter's plain text |
| Freeze point | Approval |
| Archive integrity | SHA-256 per file, stored beside the bytes |
| Browser | A developer prerequisite, like Ollama — not a build-time download |

---

## 3. Architecture

### 3.1 Pipeline

```
GET /api/applications/{id}/preview/cv        (and /letter)
  → ApplicationDocument     rows → document model
  → DocumentHtmlBuilder     document model → HTML
  → returned as text/html, displayed in an iframe

POST /api/applications/{id}/approve
  → require status DRAFT                              else 409
  → ApplicationDocument + DocumentHtmlBuilder         the SAME HTML as above
  → PdfRenderer             HTML → PDF bytes          else 503 if no browser
  → SHA-256 both files
  → persist application_archive (immutable)
  → tailored_application.status = APPROVED
```

The preview and the approve path call the same `DocumentHtmlBuilder` method. That shared call
is the whole guarantee; anything that duplicates it reintroduces drift.

### 3.2 Components

- **`ApplicationDocument`** — pure. `TailoredApplication` + `CvProfile` → a document model:
  header, experiences, the bullets 2b selected in the order it ranked them, the letter body.
- **`DocumentHtmlBuilder`** — pure. Document model → one HTML string per document. No Spring,
  no I/O. The highest-value unit tests in the slice live here. The CV's sections, in the order
  they render: header (name, headline, contact), profile summary, skills, experience (each
  role's dates, location and bullets), education (institution, degree, field and dates —
  placed after experience, the Swedish convention). The letter's sections: header, date and
  recipient, body, sign-off. A future reviewer should check the rendered document against this
  list rather than against the previous reviewer's memory of what it contained.
- **`PdfRenderer`** — the only component that drives a browser. HTML → PDF bytes.
- **`ArchiveService`** — orchestration: render, hash, persist, flip status.

### 3.3 What the documents contain

Every string an employer reads originates in the candidate's own profile rows or in the ad,
exactly as Slice 2b requires. The renderer adds layout, never content. A bullet in the HTML is
byte-identical to a bullet in `cv_experience_bullet`; the letter body is the prose the candidate
typed in `letter_prose`.

### 3.4 Why the ad is copied, not referenced

`JobMergeService` updates `job_posting.description` whenever a richer version of the same ad is
discovered, and postings are removed by employers. Referencing the live row would show a future
reader a different job than the one that was answered. The archive therefore stores the
description verbatim at approval time.

`apply_url` is stored as **text, for the candidate's reference only**. No code path in this
slice reads, follows, navigates or submits it.

**An empty letter body is allowed.** Slice 2b treats the prose as the candidate's to write, and
an application may legitimately be approved with the skeleton alone — a form that wants only a CV
is common. Approval is the candidate's judgement, and blocking it here would be the system
overruling them about their own application. The letter still renders and is still archived.

---

## 4. Data model

Flyway `V8`.

```
application_archive
    id                      BIGINT PK
    tailored_application_id BIGINT NULL REFERENCES tailored_application ON DELETE SET NULL
    job_posting_id          BIGINT NULL REFERENCES job_posting          ON DELETE SET NULL
    cv_profile_id           BIGINT NULL REFERENCES cv_profile           ON DELETE SET NULL

    job_title               VARCHAR(512) NOT NULL
    employer_name           VARCHAR(512)
    job_canonical_url       VARCHAR(1024)
    job_apply_url           VARCHAR(2048)
    job_description_text    TEXT           -- the ad verbatim, as it read that day

    letter_text             TEXT           -- for pasting into a form field
    cv_pdf                  BYTEA NOT NULL
    cv_pdf_sha256           VARCHAR(64) NOT NULL
    letter_pdf              BYTEA NOT NULL
    letter_pdf_sha256       VARCHAR(64) NOT NULL

    coverage_percent        INTEGER NOT NULL DEFAULT 0
    rendered_by             VARCHAR(128)   -- the Chromium build that produced the files
    approved_at             TIMESTAMPTZ NOT NULL
```

`BYTEA`, not `@Lob` — Slice 2a established that precedent for `cv_document.content`, because
`@Lob` maps to `oid`. Two two-page PDFs run to a few hundred kilobytes.

The SHA-256 columns make the archive evidential rather than merely descriptive: they support
"this is precisely the file that was sent", not "this is what it should have been".

Indexes: `application_archive (approved_at DESC)`,
`application_archive (tailored_application_id)`.

**No unique constraint on `tailored_application_id`.** Discarding an approved application and
re-approving a later one must produce a second archive row, not overwrite the first.

---

## 5. REST API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/applications/{id}/preview/cv` | `text/html`; exactly what will be printed |
| `GET` | `/api/applications/{id}/preview/letter` | as above |
| `POST` | `/api/applications/{id}/approve` | **extended**: render, hash, archive, freeze |
| `GET` | `/api/archive` | Approved applications, newest first, paged |
| `GET` | `/api/archive/{id}` | Metadata, the ad, and the letter text |
| `GET` | `/api/archive/{id}/cv.pdf` | The frozen bytes, `application/pdf` |
| `GET` | `/api/archive/{id}/letter.pdf` | The frozen bytes, `application/pdf` |

`POST .../approve` keeps its existing path. It gains a status guard it did not have: 2b's
`approve` accepted any application and simply set the status, which would now render and archive
a second time. It must require `DRAFT`. The separate 2b rule that a *re-tailor* over an
`APPROVED` application returns `409` lives on the tailoring endpoint and is unchanged.

---

## 6. Frontend

- **Application review** (`/applications/{id}`) gains a **Preview** panel: two tabs, CV and
  letter, each an `<iframe>` pointed at the preview endpoint. React does not re-implement the
  document — it displays the real one, which is the only way a preview can be honest.
- **Approve** shows a short spinner (rendering takes seconds, not milliseconds) and surfaces a
  missing browser as a readable message rather than a silent failure.
- **`/archive`** — the interview view: employer, role, approved date, coverage, with the ad and
  both PDFs one click away, and the letter text presented for copying.

---

## 7. Error handling

| Condition | Response |
|---|---|
| Approving an application not in `DRAFT` | `409` |
| Preview or approve for an unknown application | `404` |
| No browser available to render | `503`, naming what to install |
| Rendering fails or times out | `503`, with the underlying message |
| Requesting a PDF for an archive row that does not exist | `404` |

The application must start and every earlier slice must work with no browser installed. Only
preview and approval degrade — mirroring how Slices 2a and 2b degrade to `503` with Ollama
stopped.

---

## 8. Testing strategy

**No test may reach the network, and none may submit anything anywhere.**

- **`DocumentHtmlBuilder`** — pure: every bullet in the HTML is byte-identical to a profile
  bullet; the letter body is the stored prose; no requirement text appears that is not in the ad.
- **Round-trip fidelity** — render a fixture, then read the PDF back with **PDFBox 3.0.8**,
  already a dependency, and assert the candidate's bullets and the employer's name are
  extractable. This proves the file says what the screen said, rather than proving that a file
  was produced.
- **The åäö test** — render a name and bullets containing å, ä and ö, extract the text, assert
  they survive. Silent font-embedding failure is the classic PDF defect and the reason 2b named
  embedded fonts as a requirement.
- **Immutability** — approve, then edit the CV profile and re-run an ingest that rewrites the
  ad, then re-read the archive and assert nothing moved.
- **Independence** — discard the application, confirm the archive row survives with its FK
  nulled and its content intact.
- **Migration** — `V8` tables, the nullable `SET NULL` foreign keys, and the absence of a
  unique constraint on `tailored_application_id`.

**The browser is a developer prerequisite, like Ollama.** A first-run Playwright browser
download is network access, which the suite forbids. Tests needing a browser therefore
`assumeTrue` one is present and skip cleanly when it is not: the suite stays green on a machine
without Chromium, proves less, and says so.

---

## 9. Technical stack changes

- **Add** `com.microsoft.playwright:playwright`, version pinned at implementation time against
  the current release and verified present in the local repository — the same discipline Slice
  2b applied to Spring AI rather than assuming a version.
- Chromium is installed once by the developer, outside the build and outside the test run.
- No change to Ollama, PostgreSQL, Flyway or the frontend toolchain.

---

## 10. Success criteria

1. `./mvnw test` passes with no network access, on a machine with no browser installed —
   browser-dependent tests skip rather than fail.
2. The preview endpoint and the approve path produce the same HTML for the same application,
   asserted directly rather than by inspection.
3. Approving renders two PDFs, stores both with their SHA-256, and sets status `APPROVED`.
4. Text extracted from the rendered CV contains the candidate's bullets verbatim.
5. Swedish å, ä and ö survive a render-then-extract round trip.
6. The archived ad text is unchanged after an ingest run rewrites `job_posting.description`.
7. The archive row survives discarding and re-tailoring the application it came from.
8. Approving a second application for the same job produces a second archive row.
9. With no browser installed, preview and approval return `503` and every earlier slice works.
10. No test, and no code path reachable from a test, reads, follows or submits `apply_url`.
