# AgenticJobSeeker

Autonomous job discovery for the Swedish IT market. **Slice 1 — the spine** — discovers
job postings from three sources, deduplicates them across sources, stores them in
PostgreSQL and renders them in a dashboard.

## Sources

| Source | Type | Access |
|---|---|---|
| JobTech (Arbetsförmedlingen) | Official API | Keyless, open government data |
| Teamtailor | JSON Feed per career site | Keyless; `robots.txt` permits `ai-input` |
| Varbi | RSS per tenant | Keyless |

Teamtailor and Varbi tenants are **discovered automatically** from the apply URLs inside
JobTech ads — there is no tenant list to maintain. A single run discovers ~36 tenants
from zero configuration.

Source selection was driven by measurement, not assumption: across 2,000 Swedish IT ads,
Teamtailor backs 29.6% of them, Varbi 6.0%, while Greenhouse — the obvious international
choice — appeared **zero** times.

## Requirements

- Java 21
- Docker (for PostgreSQL)
- Node 22+

Maven is **not** required; use the bundled wrapper.

## Running

```bash
docker compose up -d                 # PostgreSQL on :5433

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

> PostgreSQL is published on **5433**, not the default 5432, to avoid colliding with
> other local Postgres instances.

## How deduplication works

The same vacancy appears in several sources under different URLs and different wording.
Identity is resolved in layers:

1. `(source, source_ad_id)` — already seen from this source
2. **`employer_job_key`** (`host|job_id`, parsed from Teamtailor `/jobs/<id>-<slug>`,
   Varbi `jobID:<id>`) — the authoritative cross-source link
3. content fingerprint — for sources whose URLs carry no job id
4. canonical URL — final fallback

A duplicate is **never discarded**. It becomes an extra `job_posting_source` row, and the
posting keeps the richest description and the most direct apply URL. That is why a job
found in both JobTech and the employer's Teamtailor site ends up with the employer's own
full text rather than the shorter agency summary.

A different `employer_job_key` always wins over an identical fingerprint — employers post
the same role in many cities with byte-identical text, and those are distinct vacancies.

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

Slice 1 — job discovery spine:
- Spec: `docs/superpowers/specs/2026-08-27-job-discovery-spine-design.md`
- Plan: `docs/superpowers/plans/2026-08-27-job-discovery-spine.md`

Slice 2a — CV profile ingestion:
- Spec: `docs/superpowers/specs/2026-08-27-cv-profile-ingestion-design.md`
- Plan: `docs/superpowers/plans/2026-08-27-cv-profile-ingestion.md`

Slice 2b — extractive tailoring:
- Spec: `docs/superpowers/specs/2026-08-28-extractive-tailoring-design.md`
- Plan: `docs/superpowers/plans/2026-08-28-extractive-tailoring.md`

Slice 2c — faithful preview and immutable archive:
- Spec: `docs/superpowers/specs/2026-08-31-faithful-preview-and-archive-design.md`
- Plan: `docs/superpowers/plans/2026-08-31-faithful-preview-and-archive.md`

## CV profile

Slice 2a ingests your CV so Slice 2b can tailor from it. Upload a PDF at
`http://localhost:5173/profile`; PDFBox extracts the text, Claude structures it, and a
validator checks that every extracted employer, title and date actually appears in your
PDF. Anything it cannot find is flagged amber for you to check rather than silently
trusted.

The guard is one-directional by design: it proves that what was extracted is present in
your CV, not that nothing was missed. Spotting omissions is what the side-by-side review
is for — raw PDF text on the left, the structured form on the right.

Extraction and tailoring both run on a **local Ollama model** — no API key, no cost, and
your CV never leaves the machine.

```bash
~/.local/ollama/bin/ollama serve &
~/.local/ollama/bin/ollama pull qwen2.5:7b-instruct
```

With Ollama stopped the application still starts and job discovery works normally; CV
upload and tailoring return `503`. Uploading the same file twice is idempotent — no second extraction, and
no risk of overwriting corrections you already made.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/profile/upload` | Upload a CV PDF and extract it |
| `GET` | `/api/profile` | Current profile |
| `PUT` | `/api/profile` | Save corrections |
| `POST` | `/api/profile/reextract` | Re-run extraction from stored text |
| `POST` | `/api/profile/approve` | Mark the profile reviewed |
| `GET` | `/api/profile/source-text` | Raw text read from the PDF |

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

Generation takes 45–90 seconds on CPU.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/jobs/{id}/tailor` | Generate the application package |
| `GET` | `/api/jobs/{id}/application` | The application for a job |
| `GET` | `/api/applications` | The queue |
| `PUT` | `/api/applications/{id}/letter` | Save your prose |
| `POST` | `/api/applications/{id}/approve` | Render, archive and mark reviewed — see below |
| `DELETE` | `/api/applications/{id}` | Discard |

### What it deliberately does not do

It does not write your cover letter. A 7B model on this hardware produced unpublishable
Swedish and invented support experience the candidate never had, so prose generation was
cut. It also does not translate, since translating is generating.

### Read the coverage figure with care

The model is generous. In live testing it cited one strong bullet against four different
requirements, including a joke requirement in the ad. Nothing it produced was untrue —
every word is yours — but a high coverage percentage means "the model found something to
point at", not "you are a strong match". The requirement-by-requirement view below the bar
is the honest read.

## Preview and archive

Open a tailored application and its **Preview** panel shows the CV and cover letter exactly as
Chromium will print them — the preview endpoint and the approve path render the same HTML, so
what you approve is what gets archived, not a description of it that could quietly drift.

Approving renders both documents, hashes each file (SHA-256), and freezes the result into
`/archive` — an immutable record you can still open months later during an interview, even
after the application it came from has been discarded or re-tailored, and even after the job
ad itself has been rewritten or removed. `apply_url` is kept in the archive for your own
reference only; nothing in the codebase reads, follows or submits it.

Rendering runs on **headless Chromium via Playwright** — local, free, no API key, and a
**developer prerequisite, exactly like Ollama**: install it once, outside the build.

```bash
cd backend
export JAVA_HOME=/usr/lib/jvm/default
./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

With no browser installed the application still starts and every earlier slice works normally;
preview and approve return `503` naming the command above. `./mvnw test` never triggers a
browser download itself — Playwright's own install step is network access, which the suite
forbids, so browser-dependent tests skip cleanly instead.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/applications/{id}/preview/cv` | The CV as HTML, exactly what will be printed |
| `GET` | `/api/applications/{id}/preview/letter` | The cover letter as HTML |
| `POST` | `/api/applications/{id}/approve` | Render, hash, archive, and freeze (extended) |
| `GET` | `/api/archive` | Approved applications, newest first, paged |
| `GET` | `/api/archive/{id}` | Metadata, the ad as it read that day, and the letter text |
| `GET` | `/api/archive/{id}/cv.pdf` | The frozen CV bytes |
| `GET` | `/api/archive/{id}/letter.pdf` | The frozen letter bytes |

## Not in this slice

Playwright application submission (Slice 3); metrics and settings (Slice 4).
