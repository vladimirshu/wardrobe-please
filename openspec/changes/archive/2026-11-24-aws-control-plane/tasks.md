## Archive handoff — 2026-11-24

Archived and synced at the owner's explicit request after implementation.
Vladimir will perform the outstanding live verification himself. Unchecked tasks
remain unchecked; archiving does not establish deployment success, acceptance or
observed costs. Optional items remain deferred. See the task and verification
records for the remaining checks. No deployment or AWS verification was run as
part of archiving.

## 1. Control Lambda

- [x] 1.1 Implement the control Lambda (Node.js, arm64, TypeScript): describe instance, start when stopped, probe the configured HTTPS CloudFront `/api/v1/health` URL with a 2 s total timeout and redirects disabled, return `{"state":...}` always with HTTP 200, no-cache headers
- [x] 1.2 Read instance id and deployment-controlled `HEALTHCHECK_URL` from environment variables; grant no SSM read permission and supply no origin secret or EC2 public IP; missing/invalid health URL returns STARTING with a configuration error logged; log AWS errors and map them to `STARTING`
- [x] 1.3 Unit tests with mocked AWS SDK clients: stopped → start called + STARTING; pending/stopping → STARTING without start (stopping) ; running + 200 → UP; running + timeout/503/redirect → STARTING; assert probe uses configured CloudFront HTTPS URL without an origin secret; missing/invalid URL → STARTING; AWS error → 200 STARTING

## 2. Schedules and sweeper

- [x] 2.1 Define EventBridge Scheduler start (Mon-Fri 08:00 with the current configuration) and stop (Mon-Fri 22:00 with the current configuration) with universal targets for `StartInstances`/`StopInstances` and a dedicated role limited to the instance. Use the IANA time zone from the shared application configuration.
- [x] 2.2 Implement the sweeper Lambda (every 5 minutes): stop when running, outside the window (same constants as the schedules, same zone) and uptime from `LaunchTime` is at least 20 minutes
- [x] 2.3 Unit tests for the window logic with a fixed clock: weekday inside/outside, no scheduled weekend start/stop, DST change day, 19 vs 20 minutes of uptime, weekday 21:50 start and weekend 21:50 wake continuing past 22:00 until its off-hours expiry
- [ ] 2.4 Verify that `LaunchTime` changes after each stop/start cycle; if not, switch to a one-time schedule per wake (document in design)
- [x] 2.5 Read and validate `wardrobe-fe/src/config/opening-hours.json` during stack setup for Scheduler and the sweeper. Document rebuilding and redeploying the frontend alongside schedule changes.

## 3. Wiring

- [x] 3.1 Create the Function URL with `AWS_IAM` auth and the least-privilege IAM roles from the design; export the Function URL for `aws-edge-hosting`
- [x] 3.2 Wire the instance id from `aws-backend-host`. Create the Function URL before the distribution, then set `HEALTHCHECK_URL=https://<distribution-domain>/api/v1/health` in a deployment step to avoid a circular infrastructure dependency; keep Lambda outside a VPC

## 4. Verification

- [ ] 4.1 Calls from the CloudFront domain: stopped → STARTING, later UP via an uncached CloudFront health probe, repeated calls safe, direct Function URL and direct EC2 access rejected; verify Lambda has no origin secret/SSM access and automated probes work without browser challenges (edge uses pay-as-you-go without WAF)
- [ ] 4.2 Observe one full weekday (08:00 start, 22:00 stop), a weekend with no scheduled start/stop and a visitor-triggered weekend wake stopped by the off-hours sweeper after 20–25 minutes

## 5. Runbook and cost

- [x] 5.1 Add to `infra/RUNBOOK.md`: how to disable the schedules, start/stop by hand, change the window or zone
- [ ] 5.2 Cost verification after the first week: Cost Explorer lines for Lambda, EventBridge and EC2 hours versus the 24/7 ceiling; confirm Budgets alerts from `aws-backend-host` fire at the expected levels

## Implementation note — 2026-11-24

Local implementation and checks are complete for the checked tasks; no deployment
or live acceptance is claimed. See [verification](verification.md). Tasks 2.4,
4.1, 4.2 and 5.2 require live stop/start cycles, the edge distribution, observation
time and billing/alert evidence. All schedules follow deployment config `schedules_enabled`, default true; live
checks and cost verification remain separate.
