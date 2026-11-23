## Context

CloudFront can route a path to a Lambda Function URL, but it cannot start or stop the origin. The SPA is client-only: the page loads from S3 whether or not the backend is up, then evaluates the shared opening schedule locally. Inside hours it probes `GET /api/v1/health` and calls control on failure; outside hours it calls control directly on arrival and before each search, avoiding a stopped-origin timeout. During startup it polls control every 3 seconds and opens immediately on a valid UP response, without client-side backend health polling. The owner chose automatic wake on page visit plus a manual "Knock" button, a fixed 20-minute session outside opening hours, and no heartbeat.

Pricing and free-tier statements below are assumptions to verify (Lambda request and duration allowances, EventBridge Scheduler invocation allowance, CloudFront free plan behavior).

## Goals / Non-Goals

**Goals:**
- Start the instance on demand and report readiness, safely callable from every visitor tab.
- Open the instance on weekdays 08:00-22:00 without any visitor.
- Stop an off-hours session after 20–25 minutes, based on launch time and the five-minute sweeper cadence.

**Non-Goals:**
- A per-day wake cap: worst case is the instance running 24/7, which is bounded and acceptable (about $8-12/month, assumption). LLM spend is limited by `demo-cost-controls`, not here.
- Heartbeats, idle detection, or session extension while a visitor is active.
- Visitor and wake-event tracking; basic traffic information uses built-in CloudFront metrics and reports (`aws-edge-hosting`).
- Authentication of visitors.

## Decisions

**1. `/api/v1/control/up` is a Lambda Function URL behind CloudFront.**
Node.js (arm64) Lambda, `AWS_IAM` auth on the Function URL with a CloudFront origin access control, so only the project's distribution can invoke it. No API Gateway (extra cost and a second origin type).
- Response: always HTTP 200 and `{"state":"UP"}` or `{"state":"STARTING"}`; unexpected AWS errors also answer 200 with `STARTING` after logging, because the SPA treats anything but a valid UP control response as "not open" and has its own stuck timeout. The CloudFront behavior uses a caching-disabled policy.

**2. State derived from EC2, no stored state.**
Algorithm per call: describe the instance; `stopped` → `StartInstances` then STARTING; `pending`/`stopping` → STARTING (a stopping instance is started on a later call, since the SPA nudges again every 3 s); `running` → probe the configured `https://<distribution-domain>/api/v1/health` with a 2 s total timeout, without following redirects → 200 gives UP, anything else STARTING. Idempotent by construction (`StartInstances` on a running instance is a no-op). The instance id and `HEALTHCHECK_URL` come from environment configuration. The health URL is deployment-controlled, never taken from a visitor request, and uses the distribution's default HTTPS domain. Lambda runs outside a VPC and needs neither the EC2 public IP nor SSM access to the origin secret. CloudFront adds `X-Origin-Verify` on the backend hop; the EC2 security group stays restricted to CloudFront. `/api/v1/health` routes to EC2, not the control Lambda, so the probe cannot recurse. Disable caching and 5xx error caching for health; WAF must permit automated probes without browser challenges.

Create the control function and Function URL first, then the distribution, then set `HEALTHCHECK_URL` in a deployment step. Do not introduce a circular infrastructure dependency between the distribution and Lambda configuration. Until configured, control returns STARTING and logs the configuration error.

**3. Schedules in EventBridge Scheduler with an explicit time zone.**
Two recurring schedules call the EC2 `StartInstances`/`StopInstances` APIs through universal targets, so no Lambda is needed for them. The stack reads the checked-in `wardrobe-fe/src/config/opening-hours.json` used by the frontend and configures weekday starts at 08:00 and weekday stops at 22:00 in Europe/Berlin with the current values. EventBridge Scheduler uses the configured IANA zone to follow daylight-saving changes. Deploy schedule changes with a rebuilt frontend. The opening interval is start-inclusive and end-exclusive in that zone. Both schedules use the same configured opening days (currently Monday to Friday). Neither schedule runs on Saturday or Sunday, so weekends are stopped by default. Visitor-triggered weekend wake remains available and the off-hours sweeper stops those sessions; there is no fixed weekend 22:00 stop.
- Alternative: cron rules in EventBridge Rules (UTC only, so DST shifts the window twice a year).

**4. Off-hours session by sweeper, based on instance launch time.**
A second Lambda runs every 5 minutes: if the instance is `running`, now is outside the window, and `now - LaunchTime >= 20 minutes`, stop it. The window parameters (days, from, to, zone) are the same stack constants used for the schedules. `LaunchTime` is assumed to be reset on each stop/start cycle (verify). A wake at 21:50 is stopped by the 22:00 schedule, so its session is shorter than 20 minutes; accepted.
- Alternative: a one-time EventBridge schedule created by `/up` at wake time (exact 20 minutes, but one more write permission and cleanup of schedules; rejected for now).
- Alternative: stop from inside the instance with a cron `shutdown` (no AWS API needed, but the window logic would be duplicated on the host).

**5. No wake-event analytics.**
The control endpoint does not persist invocation or visitor events, accept campaign tags for tracking, or require an analytics table. Basic traffic curiosity is served by built-in CloudFront metrics and reports; they do not count actual EC2 starts. Existing error/configuration logging remains operational logging.

**6. Least privilege.**
Control Lambda: `ec2:DescribeInstances`, `ec2:StartInstances` scoped to the instance ARN. It has no Parameter Store permission and no origin secret. Sweeper Lambda: describe and `StopInstances` on the instance. The Scheduler role may start/stop only this instance.

## Risks / Trade-offs

- [Crawlers or scanners that run JavaScript wake the instance] → Each off-hours session lasts about 20–25 minutes; the cost ceiling is the 24/7 case; the owner accepted auto-wake.
- [A client repeating `/up` can keep the instance up all night] → Cost bounded as above; a per-day cap is outside this change and would need a separate decision.
- [Cold start of the Lambda adds latency to the first call] → Node.js on arm64 with small bundle; the call only starts a 60-90 s boot, so extra hundreds of milliseconds do not matter.
- [Readiness depends on CloudFront as well as EC2] → This checks the visitor-facing route; edge failures, redirects or probe timeouts return STARTING. One additional CloudFront request per running-instance probe; no new network infrastructure. CloudFront-to-EC2 remains HTTP as documented in `aws-edge-hosting`.
- [Instance state transitions race (stop in progress when the visitor knocks)] → The SPA re-calls `/up` every 3 s; a stopping instance is started by a later call.
- [`StartInstances` can fail with insufficient capacity] → The call logs and answers STARTING; the SPA's 3-minute stuck state tells the visitor.
- [DST edge cases at the window boundary] → Zone-aware schedules; sweeper uses the same zone via the runtime time-zone library.

## Migration Plan

1. Deploy the stack with schedules disabled, invoke `/up` manually against the stopped instance, check STARTING then UP.
2. Enable the schedules; confirm a weekday start at 08:00 and weekday stop at 22:00, and verify neither schedule runs at weekends.
3. Wake off-hours once and confirm the instance stops about 20 minutes later.
4. Rollback: disable the schedules and Lambdas; the instance can be controlled by hand.

## Open Questions

- IaC tool: CDK (TypeScript) is the working default, Terraform the alternative; not decided by the owner.
- Custom domain: decided later; the control endpoint works on the CloudFront domain.
