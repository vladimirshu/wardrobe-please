## 1. Clients and schedule

- [x] 1.1 Implement health probe (2.5 s timeout, 2xx/429 healthy), typed control UP/STARTING parsing and abort/stale-response handling; MSW checks for invalid bodies, network, timeout and HTTP failures
- [x] 1.2 Validate VITE_OPEN_TZ/DAYS/FROM/TO at build time; implement zone/DST-aware opening checks and visitor-zone hours formatting. Generation from the shared deployment configuration is tracked by root `aws-control-plane` task 2.5.
- [x] 1.3 Test opening/closing boundaries, weekends and weekday shifts for Berlin, New York and Tokyo; document rebuild requirements and local development mode

## 2. Readiness state machine

- [x] 2.1 Implement CHECKING/CLOSED/WAKING/HEALTHY/STUCK, automatic initial routing, direct readiness on valid UP, 3 s non-overlapping control polling, 180 s timeout and timer cleanup; no OPENED flip delay or heartbeat
- [x] 2.2 Implement manual Start demo/control check, disabled Starting… while active, one-second debounce and explicit Try again from STUCK
- [x] 2.3 Test in-hours healthy loads without control, off-hours control-first routing without health requests, failed/invalid control, stop on UP/timeout, retry window reset and ignored stale results

## 3. Sage panel and integration

- [x] 3.1 Implement non-modal AvailabilityPanel with approved resting/starting/failure copy, shared Sage styling, local hours, elapsed time and accessible actions; remove door board, flip and knock concepts
- [x] 3.2 Implement 4 s intent-message rotation, 12 s announcement throttle and reduced-motion static treatment; preserve focus or return it to search when the focused panel action disappears
- [x] 3.3 Gate search and product requests while preserving drafts, snapshots, history, category and loaded products; keep local navigation, demonstration playback and product dialogs usable
- [x] 3.4 Wire pre-request schedule checks and request-failure reporting; never wake on search 429 or automatically replay a blocked search; initial catalog may resume automatically, interrupted append requires explicit action

## 4. Verification and handoff

- [x] 4.1 Add development-only health/control fixtures and optional state preview controls, excluded from production; test that production cannot bypass readiness
- [x] 4.2 Playwright flows for off-hours visit/startup, invalid control, stuck/retry, service loss, closing-time crossing, preserved draft/category/history, static demo while asleep, 429, no heartbeat and campaign tag preservation
- [x] 4.3 Check 390 px and 1440 px layouts, keyboard access to static content while waiting, focus on panel removal, polite announcements and axe-core accessibility
- [x] 4.4 Document runtime behavior and deployment verification steps; run lint, unit, E2E with mocked endpoints and production build checks.

Shared schedule generation and live verification remain pending under the root `aws-control-plane` change. The storefront README records the checks to run once the CloudFront and backend paths are deployed.
