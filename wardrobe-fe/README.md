# Wardrobe, Please storefront (eas-frontend)

Client-only static SPA for the Wardrobe, Please apparel discovery demo: a Sage-themed
natural-language clothing search with a side-by-side conversation workspace,
local category browsing, an isolated search demonstration, and schedule-aware
service availability.

## Stack

- Vite + React 18 + strict TypeScript
- Tailwind CSS v4 (Sage theme tokens defined in `src/index.css`)
- Vitest + React Testing Library + MSW for unit/component tests
- Playwright for end-to-end tests
- axe-core for accessibility checks

## Commands

```bash
npm install          # install dependencies
npm run dev          # start the Vite dev server
npm run build        # type-check and produce static files in dist/
npm run preview      # serve the production build locally
npm run lint         # ESLint
npm test             # Vitest (unit/component)
npm run test:e2e     # Playwright against dist/ (build first, then install Chromium)
npm run check:images # validate artwork, provenance and backend seed mappings
```

## Environment

Copy `.env.example` to `.env` for local development. Run `npx playwright install chromium` once before running the browser tests.

| Variable | Purpose |
|---|---|
| `VITE_DEV_BACKEND_URL` | Backend target for the Vite `/api` dev proxy (default `http://localhost:8080`) |

## Availability schedule configuration

Development and production use the checked-in schedule in
[`src/config/opening-hours.json`](src/config/opening-hours.json). The current
window is Monday to Friday, 08:00–22:00 in Europe/Berlin.

| Field | Meaning |
|---|---|
| `tz` | IANA time zone (e.g. `Europe/Berlin`) |
| `days` | comma-separated lowercase 3-letter days (`mon,tue,wed,thu,fri`) |
| `from` | opening time, 24-hour `HH:MM` (start-inclusive) |
| `to` | same-day closing time, 24-hour `HH:MM` (end-exclusive) |

`vite build` validates the file and bundles it into the storefront. To change
opening hours, edit the file and rebuild and redeploy the frontend alongside the
AWS schedule. Wiring the AWS deployment to read this file is tracked by the root
`aws-control-plane` change.

## Service availability gate (runtime behaviour)

The storefront renders immediately and evaluates the opening window locally
before touching the backend:

- **In hours** it probes `GET /api/v1/health` without displaying the panel
  (2.5 s timeout; 2xx and 429 are
  healthy). A healthy probe opens the shop silently; an unhealthy one falls
  through to the wake cycle.
- **Out of hours** it calls `GET /api/v1/control/up` first, before any health or
  search request. A valid `{"state":"UP"}` opens the shop directly; `STARTING`,
  a failed call or an invalid body keeps the gate closed and polls only the
  control endpoint every 3 seconds (never overlapping), for up to 180 seconds.
- While the gate is closed a non-modal Sage panel shows the state — "A little
  rest for the demo.", "Getting the shop ready.", or "The demo couldn't start."
  after the 180 s timeout — with local opening hours, an elapsed timer and an
  explicit action (`Start demo` / `Try again`). Search and catalog requests are
  blocked while the draft, history, snapshot, category and loaded cards are
  preserved; local navigation, the offline demonstration and product dialogs
  remain usable. There is no heartbeat: polling stops as soon as UP is confirmed.
- A search that fails on the network, times out or answers 5xx re-checks
  availability (health in hours, control out of hours) and re-opens the gate
  only when readiness is unconfirmed. A search `429` never wakes the service.

Local development has two dev-only helpers (absent from production builds):
`?gate=healthy|waking|stuck|closed` forces a phase, and `?health=fail`,
`?control=starting|error` drive the MSW health/control fixtures started by
`npm run dev`.

### Deployment verification

Once the root `aws-control-plane` / `aws-edge-hosting` / `aws-backend-host`
changes are deployed, verify the gate against the real paths: stop the backend
and confirm `/api/v1/health` fails fast (uncached 5xx), `?ref=` survives a wake,
the shop opens only on a valid `UP`, a `STARTING` cycle reaches the stuck state
after 180 s with a working `Try again`, and a search submitted after closing
time checks `/api/v1/control/up` before any health or search request.

## API contract

All requests are same-origin and relative. In development the Vite server
proxies `/api/*` to `VITE_DEV_BACKEND_URL`; in production CloudFront routes the
same paths to the backend.

- `GET /api/v1/search?message=&conversationId=&clearMemory=` (search/refinement)
- `GET /api/v1/products?limit=24[&category=][&cursor=]` (cursor-paged catalog)
- `GET /api/v1/health` (in-hours readiness probe)
- `GET /api/v1/control/up` (control-plane `{ state: "UP" | "STARTING" }`)

## Static hosting / SPA fallback

`dist/` is a static build for S3/CloudFront. The host must serve the SPA entry
(`index.html`) for `/` and `/catalog` without rewriting `/api/v1/*` responses.
Owned by the root `aws-edge-hosting` change.

## Project layout

```
src/
  components/       UI components (Header, SearchBar, ProductCard/Grid, ...)
  session/          useSearchSession (conversation state machine)
  catalog/          useCatalog (cursor-paged browsing state)
  gate/             service-availability-gate (schedule, state machine, panel)
  lib/api/          typed API clients (search, catalog, health, control)
  lib/              format, brand, examples, fixtures, categories helpers
  data/             categories.json (navigation) and catalog-images.json (imagery manifest)
public/images/categories/   alpha WebP product illustrations (incl. default.webp)
scripts/check-images.mjs    release coverage check for imagery
```

## Catalog imagery

The imagery brief, prompt template and acceptance checklist live in
`openspec/changes/archive/2026-11-09-storefront-search-ui/image-generation.md`. The baseline
manifest (`src/data/catalog-images.json`) records 17 garment keys plus
`default`. The exported alpha WebP assets are in `public/images/categories/`. Their original
transparent sources are kept in `artwork/catalog/`.
Run `npm run check:images` from the repository checkout to validate
asset coverage, export metadata and references in the backend seed.
