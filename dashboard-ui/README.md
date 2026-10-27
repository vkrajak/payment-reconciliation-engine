# dashboard-ui

Phase 7 of the Real-Time Payment Reconciliation & Exception Engine: the ops console for `dashboard-api`.
React 18 + TypeScript + Vite, TanStack Query for data, React Router, plain CSS. No component or chart library.

## Screens

| Route | What it is for |
|---|---|
| `/` Overview | How today is going: matched vs exception tally, settlement lag (p50, p95, p99, average), exceptions by cause, open backlog, engine health (one cell per partition) and a live feed |
| `/exceptions` | Triage list. Filter by status (defaults to Open) and cause, page through results; the filters live in the address, so a view can be bookmarked or shared |
| `/exceptions/:id` | Drill-down. Each source's amount, currency and time side by side with differences highlighted, a missing source shown as "No event", which rule fired and what it compared, raw events, and the resolve form |
| `/lookup` | Find one transaction by reference: status, cause, sources, timestamps, and whether the answer came from the cache or the database |

Behaviour worth knowing:

- **Live data.** Metrics refetch every 30s for today (the server caches them for 5 minutes) and not at all for past dates. Engine health refetches every 10s. The live feed uses `EventSource`, which reconnects by itself; the panel shows Connecting, Live or Reconnecting. The server sends matches as batches, so the UI shows a rate and a running total, never a row per match.
- **The exceptions list never reshuffles under you.** New exceptions that match your filters appear as a banner ("1 new exception arrived. Show it") and are added only when you click.
- **Resolving.** A name and a note are both required (the button stays disabled until they are given). If someone else resolved the exception first (HTTP 409), the page says so and reloads their resolution. A successful resolve refreshes the list, the overview figures and any cached transaction status.
- **Operator name.** `dashboard-api` v1 has no login, so the name is only an audit label, sent as `resolvedBy`. It is set from the header ("Resolving as …") or the first resolve, and remembered in this browser.
- **Time.** "Today" and every displayed time use `VITE_TIMEZONE` (default `Asia/Kolkata`), not the browser's zone.
- **Errors say what happened** ("Cannot reach the dashboard API…") with **Try again** where it helps. Unknown exceptions and transactions get a plain sentence, not a raw error. Dark mode follows the OS setting.

## Run in development

Needs **Node.js 20+** and `dashboard-api` on `localhost:8086`.

```bash
cd dashboard-ui
npm install
npm run dev          # http://localhost:3000, /api is proxied to dashboard-api
```

PowerShell, if the API is elsewhere: `$env:DASHBOARD_API_URL="http://host:8086"; npm run dev`

| Variable | Default | Purpose |
|---|---|---|
| `DASHBOARD_API_URL` | `http://localhost:8086` | Dev proxy target (`npm run dev` only) |
| `VITE_API_BASE_URL` | empty | Only if the UI is served from a different origin than the API (then CORS applies; the API allows localhost:3000 and :5173 by default) |
| `VITE_TIMEZONE` | `Asia/Kolkata` | Display timezone and the meaning of "today" (baked in at build time) |

## Run in Docker

```bash
docker build -t dashboard-ui dashboard-ui
docker run -p 3000:80 -e DASHBOARD_API_URL=http://host.docker.internal:8086 dashboard-ui
```

Or merge `docker-compose.dashboard-ui.snippet.yml` into your compose file and `docker compose up -d dashboard-ui`.

The image is a multi-stage build (Node builds, nginx serves). nginx also:

- proxies `/api/` to `DASHBOARD_API_URL` (default `http://dashboard-api:8086`), so the browser sees one origin and CORS never applies
- serves the live stream unbuffered with a 1-hour read timeout (without this the feed would stall)
- falls back to `index.html` for deep links such as `/exceptions/12`, and never caches it; hashed files under `/assets/` are cached for a year
- looks the API's address up per request (`NGINX_RESOLVER`, default Docker's `127.0.0.11`), so the UI starts even if the API isn't up yet and follows it across restarts
- answers `GET /healthz`, which the image's `HEALTHCHECK` uses

Outside Docker Compose (for example Kubernetes in Phase 9) set `NGINX_RESOLVER` to that cluster's DNS server.

## Scripts

```bash
npm run typecheck   # tsc --noEmit
npm test            # vitest (jsdom), 34 tests
npm run build       # typecheck + production bundle in dist/
```

## Tests

| File | Covers |
|---|---|
| `lib/format.test.ts`, `lib/compare.test.ts` | Lag units, en-IN grouping, "today" in a timezone that differs from UTC; the three source columns always shown, amount formatting, classification described in words |
| `hooks/useLiveStream.test.tsx` | connecting, live, reconnecting; newest-first dedupe and cap; match rate over a rolling window; malformed events ignored; stream closed on unmount |
| `hooks/useOperator.test.tsx` | name trimmed, stored in the browser, shared across components |
| `api/client.test.ts` | query building, JSON POST, server messages vs non-JSON errors, unreachable API (status 0), reference encoding |
| `pages/OverviewPage.test.tsx`, `LookupPage.test.tsx` | Tally, lag, causes, degraded engine, empty day, API down; lookup result, trimmed input, 404 sentence |
| `pages/ExceptionsPage.test.tsx` | Default Open filter, filters from the address, "All statuses", paging, empty message, live "new exception" banner (ignores other causes), load failure with retry |
| `pages/ExceptionDetailPage.test.tsx` | Side-by-side diff with differing row and missing source, resolve button gating, resolve request body and audit view, remembered name, 409 handled, read-only when resolved, unknown or invalid id |

The nginx config was checked with a real nginx against a fake API (SPA fallback, caching headers, proxy, unbuffered live stream). The Docker image build itself has not been run.

## Layout

```
src/
  api/         types.ts (mirrors dashboard-api), client.ts (fetch wrapper, ApiError, endpoints)
  hooks/       useLiveStream, useOperator
  lib/         format.ts, compare.ts
  components/  Layout, OperatorControl, TallyBar, LagTable, CodeBreakdown, EnginePanel, LivePanel, StatusBadge, ErrorBanner
  pages/       OverviewPage, ExceptionsPage, ExceptionDetailPage, LookupPage
  test/        setup, fakes (EventSource, fetch), fixtures
nginx.conf.template, Dockerfile, .dockerignore
```

## Known limits

- **No authentication**, matching `dashboard-api` v1. The operator name is not verified. Put the UI behind your gateway or add login before exposing it.
- **No Acknowledge or reopen**: the API cannot set `ACKNOWLEDGED` (it only appears as a status and filter value).
- **The raw `recon-results` match payload is not displayed**; its schema is not in the blueprint, so the feed shows rates and totals.
- **The live exception feed is best-effort** (the API follows row ids); the list is authoritative, so use **Refresh** if something looks missing.
- **No trend view**: the API returns one date per call.
