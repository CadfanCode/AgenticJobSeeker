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

## CV profile

Slice 2a ingests your CV so Slice 2b can tailor from it. Upload a PDF at
`http://localhost:5173/profile`; PDFBox extracts the text, Claude structures it, and a
validator checks that every extracted employer, title and date actually appears in your
PDF. Anything it cannot find is flagged amber for you to check rather than silently
trusted.

The guard is one-directional by design: it proves that what was extracted is present in
your CV, not that nothing was missed. Spotting omissions is what the side-by-side review
is for — raw PDF text on the left, the structured form on the right.

Extraction needs a model credential:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Without it the application still starts and job discovery works normally; CV upload
returns `503`. Uploading the same file twice is idempotent — no second extraction, and
no risk of overwriting corrections you already made.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/profile/upload` | Upload a CV PDF and extract it |
| `GET` | `/api/profile` | Current profile |
| `PUT` | `/api/profile` | Save corrections |
| `POST` | `/api/profile/reextract` | Re-run extraction from stored text |
| `POST` | `/api/profile/approve` | Mark the profile reviewed |
| `GET` | `/api/profile/source-text` | Raw text read from the PDF |

## Not in this slice

Tailored CV and cover-letter generation, translation, PDF rendering and the approval
queue (Slice 2b); Playwright application submission (Slice 3); metrics and settings
(Slice 4).
