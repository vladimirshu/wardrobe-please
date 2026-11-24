## Why

To keep cost near zero, the backend instance runs only on weekdays from 08:00 to 22:00 and is otherwise stopped. A visitor arriving while it is stopped must be able to wake it, without tracking who woke it. The page's JavaScript can only reach the backend through CloudFront, which cannot start a stopped instance, so a small always-on control plane (a Lambda) is needed. It must cost nothing when idle and must bound the cost when someone keeps waking the instance.

## What Changes

- Add `GET /api/v1/control/up`: an idempotent Lambda endpoint that starts the EC2 instance if it is stopped and answers `{"state":"UP"|"STARTING"}` (always HTTP 200). UP only when the instance is running and its readiness endpoint answers 200 through the project's CloudFront distribution over HTTPS; Lambda never connects directly to EC2 or reads the origin secret.
- Add EventBridge Scheduler rules in a configurable time zone: start Monday to Friday 08:00, stop Monday to Friday 22:00. No scheduled start or stop runs at weekends; the backend stays stopped by default, with visitor-triggered wake still available.
- Add a sweeper (every 5 minutes) that stops the instance when it is outside the open window and has been running for at least 20 minutes, so an off-hours wake lasts about 20–25 minutes.
- The SPA sends no heartbeat. It calls Lambda immediately outside opening hours (on arrival and before a search), after a failed in-hours health probe, and while waiting for startup. A valid UP response confirms readiness without a client-side backend health probe.

## Capabilities

### New Capabilities
- `service-wake-control`: the `/api/v1/control/up` contract, idempotency, UP/STARTING decision, access restriction.
- `availability-schedule`: weekday opening and closing schedules, weekends stopped by default, and the 20-minute off-hours session.

### Modified Capabilities
<!-- none -->

## Impact

- New infrastructure code and two Lambda functions (control, sweeper) plus two schedules; no analytics database.
- Depends on: `aws-backend-host` (instance id), `aws-edge-hosting` (route `/api/v1/control/*` to the Lambda Function URL and `/api/v1/health` to EC2; distribution domain for readiness probes), `api-versioned-search-path` (`/api/v1/health`).
- Cross-project contract: the SPA (`service-availability-gate`) relies on the exact JSON shape and on `/up` being safe to call repeatedly.
- Cost: Lambda and EventBridge Scheduler are expected to stay within free allowances at this traffic (assumptions to verify); the real cost driver is instance hours, bounded by 24/7 operation.
