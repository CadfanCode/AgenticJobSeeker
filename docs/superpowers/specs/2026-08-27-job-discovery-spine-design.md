# Slice 1 — The Spine: Job Discovery & Persistence

**Status:** approved design, ready for implementation planning
**Date:** 2026-08-27
**Project:** AgenticJobSeeker — Autonomous AI Job Application Engine

---

## 1. Context

The parent brief describes a four-subsystem product: job discovery, AI CV/cover-letter
tailoring, Playwright form submission, and a React dashboard. That is too much for one
spec, and the brief's build order is horizontally layered — all entities, then all AI,
then all automation — so nothing runs end-to-end until the final step.

The project is therefore cut into four vertical slices, each producing a system that
actually runs:

| Slice | Delivers |
|---|---|
| **1 — Spine** (this spec) | Postgres + entities + real job sources + dedup + REST + dashboard list |
| 2 — Tailoring | Spring AI CV/cover-letter agents, structured output, PDF rendering, approval queue |
| 3 — Submission | Playwright, per-ATS adapters, safety flag, application records |
| 4 — Ops | Metrics, settings, CV upload, scheduling |

Each slice gets its own spec → plan → build cycle. This document covers Slice 1 only.

### Goal

Real Swedish IT job postings land in PostgreSQL, deduplicated across sources, and render
in a browser.

### Non-goals for Slice 1

No AI, no Spring AI dependency, no Playwright, no PDF generation, no application
submission, no authentication, no match scoring. `UserProfile` and `ApplicationRecord`
from the brief are deliberately deferred to Slices 2–3.

---

## 2. Source research findings

All findings below were verified by live probe on 2026-08-27, not assumed.

### 2.1 Market structure

A histogram of `application_details.url` across **2,000 Swedish IT ads** from JobTech
(`occupation-field=apaJ_2ja_LuF`) gives the real ATS distribution:

| ATS vendor | Share of SE IT ads |
|---|---|
| (other / direct employer domains) | 39.0% |
| **Teamtailor** | **29.6%** |
| Varbi | 6.0% |
| ReachMee | 5.5% |
| Recman | 4.7% |
| Talentech | 2.6% |
| SmartRecruiters | 2.0% |
| Workday | 1.9% |
| Ashby | 0.3% |
| Lever | 0.2% |
| **Greenhouse** | **0 hits in 2,000 ads** |

Teamtailor's true share exceeds 29.6%, because many tenants serve from custom domains
(e.g. `jobs.avaron.se`) that carry Teamtailor's signature
`/jobs/<id>-<slug>/applications/new` URL shape.

**Consequence:** Greenhouse, Lever and Ashby are the wrong sources for the Swedish
market despite being the obvious international choices.

### 2.2 Swedish job boards evaluated

| Source | Independent of JobTech? | Machine-readable? | Legal posture |
|---|---|---|---|
| JobTech / Platsbanken | *is* the source | Official keyless API | Open government data |
| **Teamtailor** | **Yes** — direct from employer | **Official keyless JSON Feed** | robots allows; `ai-input=yes` |
| **Varbi** | **Yes** — direct from employer | **Public RSS per tenant** | robots allows |
| ReachMee | Yes, but not enumerable | No feed; signed `validator` URLs | page fetch only |
| thehub.io | Yes (Nordic startups) | Nuxt state only, no public API | robots permissive |
| sverigedev.se | **No — JobTech mirror** | Inertia JSON | allowed |
| jobbland.se | **No — mirror** | HTML only | — |
| jobbsafari.se | Mostly mirror | **`robots: Disallow: /api`** | restricted |
| jobb.blocket.se | — | **dead, no DNS** | — |
| metrojobb.se | — | 1.3 KB stub page | — |
| LinkedIn / Indeed | Yes | scraping only | **ToS violation, ban risk** |

Key finding: **most Swedish "job boards" are JobTech mirrors or closed.** The genuinely
additive Swedish sources are the ATS platforms underneath them.

`sverigedev.se` was verified as a mirror directly: its `ad_id` `31404250` resolves to
JobTech ad `31404250` with identical headline, employer (Avaron AB), municipality and a
byte-identical apply URL tagged `?promotion=2165239-arbetsformedlingen`. Its 2,319 IT ads
are a strict subset of JobTech's 2,704 under `occupation-field=Data/IT`.

### 2.3 Source mechanics

**JobTech** (`jobsearch.api.jobtechdev.se`) — keyless. `offset` hard-caps at **2000**
(HTTP 400 beyond), so criteria must be narrow enough to stay under that ceiling.
A separate `jobstream.api.jobtechdev.se/stream?date=` change feed returns ~9,600
records/day carrying `removed` / `removed_date` / `timestamp`, giving job expiry for
free — deferred to a later slice, but the port is shaped so it can replace polling.

**Teamtailor** — `https://<host>/jobs.json` returns JSON Feed v1
(`application/feed+json`), keyless, with `next_url` pagination (`?page=N&per_page=100`)
and a `_jobposting` extension containing full **schema.org `JobPosting`** with complete
HTML descriptions. `robots.txt` permits `/jobs*` and declares
`Content-Signal: search=yes, ai-train=no, ai-input=yes` — the CV-tailoring use case in
Slice 2 is *ai-input*, explicitly permitted. A `/jobs.md` Markdown alternate is
advertised via `Link` header and is useful for LLM input in Slice 2.

Measured unique coverage versus JobTech (upper bound; JobTech side used a fuzzy
`q=<employer>` capped at 100 hits):

| Employer | Teamtailor | Also in JobTech | Teamtailor-only |
|---|---|---|---|
| Knowit | 98 | 35 | 78 (80%) |
| Softhouse | 10 | 8 | 2 (20%) |
| Avaron | 94 | 95 | 13 (14%) |
| HumanIT | 17 | 17 | 0 (0%) |

**Varbi** — `https://<tenant>.varbi.com/what:rssfeed/` returns `application/rss+xml`,
keyless, with full descriptions and a stable `guid`. Verified on 4 tenants: 22, 187, 403
and 13 items. Heavily public-sector (Kriminalvården, Transportstyrelsen, VG-Region).

**ReachMee** — serves from two shared hosts (`web103`, `web106`) with per-tenant
`validator=<hash>` signed URLs. No feed found at any probed path, and jobs **cannot be
enumerated** without the tenant hash. The apply URL supplied by JobTech does render a
full job page. ReachMee is therefore an *enrichment* source, not a discovery source.

---

## 3. Architecture

### 3.1 Two ports, not one

Sources differ by **role**, and conflating them is the main design trap:

- **`JobSource`** — can enumerate jobs independently.
  Implementations: `JobTechSource`, `TeamtailorSource`, `VarbiSource`.
- **`JobEnricher`** — can only fetch a job whose URL is already known.
  Implementations deferred; interface defined in Slice 1 so ReachMee and the 39%
  long tail have a home in a later slice.

### 3.2 Self-expanding tenant discovery

Teamtailor and Varbi are per-tenant sources. Hand-maintaining tenant lists is rejected.
JobTech instead **seeds** the other two:

```
JobTech ingest
  → read application_details.url host
  → classify vendor (teamtailor | varbi | reachmee | other)
  → upsert ats_tenant registry
  → Teamtailor/Varbi adapters poll registered tenants' feeds
```

A single JobTech run over 2,000 ads discovered **46 Varbi tenants** and **23 live
Teamtailor feeds** with zero configuration. The registry grows itself as new employers
appear in the market.

Tenant probing is best-effort: 37 of 60 probed hosts had no Teamtailor feed. A tenant
that 404s is a normal outcome, recorded and deactivated — not an error.

### 3.3 Identity and deduplication

The brief's proposed dedup key — SHA-256 of the job URL — fails on the first real case
tested, because the same ad carries different URLs on `sverigedev.se`,
`arbetsformedlingen.se` and the employer's own Teamtailor domain. Three layers are used
instead:

1. **`(source, source_ad_id)`** — natural key per source; prevents re-ingest.
2. **`canonical_url`** — normalized: strip tracking/query params (notably `?promotion=`),
   lowercase host, drop leading `www.`, drop trailing slash.
3. **Content fingerprint** — `SHA-256(employer_org_number | normalized_title |
   description[:512])`. This is the layer that catches the same job arriving from
   JobTech *and* Teamtailor.

`normalized_title` lowercases, strips punctuation and collapses whitespace.

### 3.4 Merge policy

A duplicate is **never discarded**. On fingerprint collision the existing `job_posting`
gains an additional `job_posting_source` row, and the posting is upgraded in place:

- **description** — keep the longest/richest (Teamtailor's employer-authored HTML beats
  JobTech's plain text)
- **apply_url** — prefer the most direct employer URL over an aggregator redirect
- **last_seen_at** — refreshed on every sighting

This is why Teamtailor earns its place even for an employer at 0% unique jobs: it
contributes better content for postings JobTech already had.

### 3.5 Applying search criteria across heterogeneous sources

The three discovery sources accept filtering at different points, so criteria are
applied in two places:

- **JobTech — server-side.** `search_criteria.query`, `municipality_codes` and
  `occupation_field_codes` map directly onto `jobsearch` request parameters. Only
  matching ads are ever fetched.
- **Teamtailor and Varbi — client-side.** Tenant feeds are whole-catalogue and offer no
  server-side query. The adapter fetches the full feed, then filters locally: keyword
  match against title and description, plus municipality match where the payload
  supplies it.

A posting is ingested if it matches **any** enabled `search_criteria` row. Non-matching
feed entries are counted on the ingest run but not persisted, so `fetched` may
substantially exceed `created` for tenant sources — that is expected, not a fault.

---

## 4. Data model

Flyway-migrated PostgreSQL.

```
job_posting          id, fingerprint UNIQUE, canonical_url, title,
                     employer_name, employer_org_number, municipality,
                     description, language, ats_vendor, apply_url,
                     published_at, deadline_at, status,
                     first_seen_at, last_seen_at

job_posting_source   id, job_posting_id FK, source, source_ad_id,
                     source_url, raw_payload JSONB, fetched_at
                     UNIQUE(source, source_ad_id)

ats_tenant           id, vendor, host, feed_url, discovered_from,
                     last_polled_at, etag, active

search_criteria      id, name, query, municipality_codes,
                     occupation_field_codes, enabled

ingest_run           id, source, started_at, finished_at,
                     fetched, created, merged, errors, status
```

One `ingest_run` row is written **per source per triggered run** — a single trigger
over three sources produces three rows. This keeps per-source isolation (Section 7)
visible in the run history: one source can be `FAILED` while its siblings are
`COMPLETED`.

`raw_payload JSONB` is retained deliberately: Slice 2 can re-derive salary, requirements
and schema.org `_jobposting` fields without re-crawling any source.

Indexes: `job_posting.fingerprint` (unique), `job_posting.canonical_url`,
`job_posting.published_at`, `job_posting_source(source, source_ad_id)` (unique),
`ats_tenant.host` (unique).

`status` in Slice 1 is limited to `DISCOVERED`; later slices add `PROCESSING`,
`READY_FOR_REVIEW`, `APPLIED`, `FAILED`.

---

## 5. REST API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/jobs?q=&municipality=&source=&vendor=&page=&size=` | paged, filtered list |
| `GET` | `/api/jobs/{id}` | detail including all source rows |
| `POST` | `/api/ingest/run` | manual ingest trigger |
| `GET` | `/api/ingest/runs` | run history |
| `GET` | `/api/criteria` | list search criteria |
| `POST` | `/api/criteria` | create search criteria |
| `GET` | `/api/stats` | dashboard counts |

Scheduled polling per source via `@Scheduled`, with the manual trigger above for
development and on-demand runs.

---

## 6. Frontend

Vite + React + TypeScript + Tailwind CSS.

- **Job list** — search box, municipality / source / ATS-vendor filters, source and
  vendor badges, pagination
- **Job detail** — full description, employer, apply link, all contributing sources
- **Stats header** — total jobs, jobs by source, last ingest run

Read-only in Slice 1. The approval queue arrives in Slice 2.

---

## 7. Error handling

- **Per-source isolation.** One adapter throwing must not fail the whole run; partial
  success is recorded on `ingest_run`.
- **Conditional GET.** Store `etag` per `ats_tenant`; send `If-None-Match` and treat 304
  as "no change".
- **Rate limiting.** Per-host throttling across hundreds of tenant feeds.
- **Backoff.** Exponential backoff on failure; deactivate a tenant after repeated
  failures rather than retrying forever.
- **JobTech offset ceiling.** Criteria producing more than 2,000 hits must be split, or
  the run truncates with a recorded warning — never a silent partial result.

---

## 8. Testing strategy

Test-driven throughout.

- **Adapter tests** run against WireMock serving **real captured payloads** — JobTech
  ads, a Teamtailor `jobs.json`, and Varbi RSS, all captured during research.
- **Repository and dedup tests** use Testcontainers PostgreSQL.
- **Highest-value test — cross-source merge**, seeded with the verified real case:
  JobTech ad `31404250` and the Teamtailor posting on `jobs.avaron.se` must resolve to
  **one** `job_posting` with **two** `job_posting_source` rows, retaining the richer
  Teamtailor description and the direct employer apply URL.
- **Contract tests** confirm each adapter maps its source payload to the common model.

---

## 9. Technical stack

- Java 21, **Spring Boot 4.1.1.RELEASE**
- **Maven Wrapper (`mvnw`)** — Maven is not installed on this machine; the wrapper
  was verified to bootstrap Maven 3.9.16 unaided

> **Deviation from the original brief.** The brief specified Spring Boot 3.x. As of
> 2026-08-27 `start.spring.io` no longer offers any 3.x line — the available versions
> are 4.0.8.RELEASE and 4.1.1.RELEASE (default). Slice 1 therefore targets **4.1.1**.
> Two Boot 4 renames matter for implementation and are easy to get wrong:
> the web starter is `spring-boot-starter-webmvc` (not `-web`), and Flyway is pulled
> via `spring-boot-starter-flyway` (not bare `flyway-core`).
- PostgreSQL via Docker Compose
- Spring Data JPA + Flyway
- Spring Web, WireMock, Testcontainers
- Vite + React + TypeScript + Tailwind CSS

Explicitly **not** in Slice 1: Spring AI, Playwright, PDFBox.

---

## 10. Success criteria

1. `docker compose up` plus `./mvnw spring-boot:run` yields a running system.
2. An ingest run pulls real Swedish IT jobs from JobTech into PostgreSQL.
3. Teamtailor and Varbi tenants are auto-discovered from JobTech apply-URLs and polled.
4. A job present in two sources is stored once with two source rows and the richer
   description.
5. The React dashboard lists, filters and displays those jobs in a browser.
6. A failing source degrades the run partially, never totally.
